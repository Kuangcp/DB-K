package tree

import engine.model.DbObjectMeta

/**
 * Redis key 层级（前缀树，纯逻辑，可单测）。
 *
 * 分隔符非空时把 `a:b:c` 拆成命名空间链；叶子节点保留完整 key（预览/复制仍用完整 key），
 * 展示名只取最后一段。分隔符为空或 key 不含分隔符时退化为平铺（每棵根都是叶子）。
 *
 * 边界：某 key 同时是另一个 key 的前缀（如 `a` 与 `a:b`）时，该节点既带 [KeyTreeNode.key]
 * 又带 [KeyTreeNode.children]，渲染层会把「命名空间」与「真实 key」分别展示。
 */
data class KeyTreeNode(
    /** 当前段（展示名）。根子节点为第一段。 */
    val segment: String,
    /** 从根到当前节点的完整前缀（用分隔符连接）。 */
    val path: String,
    /** 该节点是否为真实 key（既是前缀又是 key 时同时携带）。 */
    val key: DbObjectMeta? = null,
    val children: List<KeyTreeNode> = emptyList(),
)

fun buildKeyTree(keys: List<DbObjectMeta>, separator: String): List<KeyTreeNode> {
    if (separator.isEmpty()) {
        return keys.sortedBy { it.name }.map { KeyTreeNode(segment = it.name, path = it.name, key = it) }
    }
    // 内部 trie 节点（用 path 区分不同层级下的同名段，如 a:b 与 x:b 的段 b）。
    class Node(val segment: String, val path: String) {
        val children = LinkedHashMap<String, Node>()
        var key: DbObjectMeta? = null
    }
    val root = Node("", "")
    for (meta in keys) {
        val parts = meta.name.split(separator)
        if (parts.size <= 1) {
            val node = root.children.getOrPut(meta.name) { Node(meta.name, meta.name) }
            node.key = meta
        } else {
            var cur = root
            for (part in parts) {
                val path = if (cur.path.isEmpty()) part else cur.path + separator + part
                cur = cur.children.getOrPut(part) { Node(part, path) }
            }
            cur.key = meta
        }
    }
    fun toTree(node: Node): KeyTreeNode = KeyTreeNode(
        segment = node.segment,
        path = node.path,
        key = node.key,
        children = node.children.values.map(::toTree).sortedBy { it.segment },
    )
    return root.children.values.map(::toTree).sortedBy { it.segment }
}

/** 该节点覆盖的 key 数（含自身 key + 全部后代叶子）。 */
fun KeyTreeNode.descendantKeyCount(): Int =
    (if (key != null) 1 else 0) + children.sumOf { it.descendantKeyCount() }
