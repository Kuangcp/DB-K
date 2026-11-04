package sync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 系统 `git` 的薄封装（不做业务判断，不抛异常 —— 失败信息放在 [GitResult] 里）。
 *
 * 为什么不用 JGit：目标用户是开发者，ssh key / credential helper / 2FA 已在系统 git 里配好；
 * 也不给安装包增加 ~12MB。代价是运行环境必须有 git（[isAvailable] 探测 + UI 提示）。
 *
 * 约定：所有命令带 `-c core.quotepath=false`（中文文件名不转义），stdin 关闭（绝不进入
 * 交互模式），stderr 合并进 [GitResult.out]（一次读完，避免缓冲区互锁）。
 */
class GitRepo(val workTree: Path) {

    data class GitResult(val exit: Int, val out: String) {
        val ok: Boolean get() = exit == 0
    }

    companion object {
        fun isAvailable(): Boolean = version() != null

        fun version(): String? = runCatching {
            val proc = ProcessBuilder("git", "--version").redirectErrorStream(true).start()
            proc.outputStream.close()
            val text = proc.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            if (proc.waitFor() == 0) text.ifBlank { null } else null
        }.getOrNull()
    }

    // ---------- 基础执行 ----------

    fun run(vararg args: String): GitResult = exec(args.toList(), workTree)

    private fun exec(args: List<String>, dir: Path): GitResult {
        runCatching { Files.createDirectories(dir) }
        val bytes = execBytes(args, dir) ?: return GitResult(-1, "failed to start git")
        val text = bytes.out.toString(Charsets.UTF_8).trimEnd()
        return GitResult(bytes.exit, text)
    }

    private class RawResult(val exit: Int, val out: ByteArray)

    private fun execBytes(args: List<String>, dir: Path): RawResult? = runCatching {
        val pb = ProcessBuilder(listOf("git", "-c", "core.quotepath=false") + args)
            .directory(dir.toFile())
            .redirectErrorStream(true)
        val proc = pb.start()
        proc.outputStream.close()
        val out = proc.inputStream.readBytes()
        RawResult(proc.waitFor(), out)
    }.getOrNull()

    // ---------- 仓库生命周期 ----------

    fun init(): GitResult {
        val modern = run("init", "-b", "main")
        if (modern.ok) return modern
        val legacy = run("init")
        if (!legacy.ok) return legacy
        return run("symbolic-ref", "HEAD", "refs/heads/main")
    }

    fun setLocalIdentity(name: String = "db-k", email: String = "db-k@localhost"): GitResult {
        run("config", "user.name", name)
        return run("config", "user.email", email)
    }

    /** 克隆到 [workTree]；目标目录已存在且非空时直接失败（绝不覆盖用户目录）。 */
    fun clone(remote: String, branch: String): GitResult {
        if (Files.exists(workTree)) {
            val nonEmpty = runCatching { Files.list(workTree).use { it.findAny().isPresent } }.getOrDefault(true)
            if (nonEmpty) return GitResult(1, "clone target already exists and is not empty: $workTree")
        }
        val parent = workTree.toAbsolutePath().parent
        val result = exec(listOf("clone", "--branch", branch, remote, workTree.toAbsolutePath().toString()), parent)
        if (result.ok) setLocalIdentity()
        return result
    }

    fun hasCommits(): Boolean = run("rev-parse", "--verify", "HEAD").ok

    fun currentBranch(): String = run("rev-parse", "--abbrev-ref", "HEAD").out.trim()

    fun setRemote(remote: String, url: String): GitResult {
        val exists = run("remote", "get-url", remote).ok
        return if (exists) run("remote", "set-url", remote, url) else run("remote", "add", remote, url)
    }

    // ---------- 暂存 / 提交 ----------

    fun add(paths: List<String>): GitResult =
        if (paths.isEmpty()) run("add", "-A") else run(*(listOf("add", "-A", "--") + paths).toTypedArray())

    /** 有暂存变更就提交；返回是否真的产生了提交。 */
    fun commit(message: String): Boolean {
        val hasStaged = !run("diff", "--cached", "--quiet").ok
        if (!hasStaged) return false
        return run("commit", "-m", message).ok
    }

    // ---------- 远端 ----------

    fun fetch(remote: String, branch: String): GitResult = run("fetch", "--prune", remote, branch)

    fun merge(ref: String, allowUnrelated: Boolean = false): GitResult {
        val args = buildList {
            add("merge")
            add("--no-edit")
            if (allowUnrelated) add("--allow-unrelated-histories")
            add(ref)
        }
        return run(*args.toTypedArray())
    }

    fun mergeInProgress(): Boolean {
        val path = run("rev-parse", "--git-path", "MERGE_HEAD").out.trim()
        if (path.isEmpty()) return false
        val resolved = workTree.resolve(path).normalize()
        return Files.exists(resolved)
    }

    fun mergeAbort(): GitResult = run("merge", "--abort")

    fun hasMergeBase(ref: String): Boolean = run("merge-base", "HEAD", ref).ok

    fun resetHard(ref: String): GitResult = run("reset", "--hard", ref)

    fun push(remote: String, branch: String): GitResult = run("push", remote, "HEAD:refs/heads/$branch")

    // ---------- 冲突 ----------

    /** 冲突路径（未合并路径），相对工作树。 */
    fun conflictedPaths(): List<String> =
        run("diff", "--name-only", "--diff-filter=U").out.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** `git show :<stage>:<path>` 的原始字节（stage 2 = ours，3 = theirs）。 */
    fun stageBlob(stage: Int, path: String): ByteArray {
        val raw = execBytes(listOf("show", ":$stage:$path"), workTree) ?: return ByteArray(0)
        return if (raw.exit == 0) raw.out else ByteArray(0)
    }

    // ---------- 工作树文件 ----------

    fun readFile(relativePath: String): String {
        val p = workTree.resolve(relativePath)
        return runCatching { Files.readString(p) }.getOrDefault("")
    }

    fun writeFile(relativePath: String, bytes: ByteArray) {
        val target = workTree.resolve(relativePath)
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, ".${target.fileName}", ".tmp")
        try {
            Files.write(tmp, bytes)
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            Files.write(target, bytes)
        }
    }

    fun deleteFile(relativePath: String) {
        Files.deleteIfExists(workTree.resolve(relativePath))
    }

    // ---------- 历史 ----------

    fun logOneLine(limit: Int = 20): List<String> =
        run("log", "--format=%h %s", "-n", limit.toString()).out.lines().filter { it.isNotBlank() }
}
