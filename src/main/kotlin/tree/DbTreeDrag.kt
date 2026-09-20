package tree

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates

/** 拖拽落点指示样式：无 / 插到行前 / 插到行后 / 放入容器（文件夹 / 根级）。 */
enum class DropIndicator { None, InsertBefore, InsertAfter, Into }

/** 左侧树拖拽源：数据源（连接）或文件夹。 */
sealed class TreeDragPayload {
    /** [fromFolderId] = 拖动前所属文件夹（null=根级），用于决定是否显示「移到根级」落点。 */
    data class Connection(val id: String, val fromFolderId: String?) : TreeDragPayload()
    data class Folder(val id: String) : TreeDragPayload()
}

/** 拖放目标。 */
sealed class TreeDropTarget {
    /** 连接插入到 folderId（null=根级）下第 insertIndex 位。 */
    data class ConnectionSlot(val folderId: String?, val insertIndex: Int) : TreeDropTarget()

    /** 连接/文件夹移入某文件夹（追加到末尾）。 */
    data class IntoFolder(val folderId: String) : TreeDropTarget()

    /** 文件夹插入到 parentFolderId（null=根级）下第 insertIndex 位。 */
    data class FolderSlot(val parentFolderId: String?, val insertIndex: Int) : TreeDropTarget()
}

/** 行的拖放描述符：把「指针落在某行」解析成具体的 [TreeDropTarget]。 */
sealed interface RowDropDesc {
    val rowKey: String

    data class ConnectionDesc(
        val connectionId: String,
        val folderId: String?,
        val connectionIndex: Int,
    ) : RowDropDesc {
        override val rowKey = "c:$connectionId"
    }

    data class FolderDesc(
        val folderId: String,
        val parentFolderId: String?,
        val folderIndex: Int,
    ) : RowDropDesc {
        override val rowKey = "f:$folderId"
    }

    /** 「移到根级」落点（仅拖连接时显示；连接追加到根级末尾）。 */
    data object RootDesc : RowDropDesc {
        override val rowKey = "drop:root"
    }
}

internal data class ResolvedDrop(
    val rowKey: String,
    val indicator: DropIndicator,
    val target: TreeDropTarget,
)

/**
 * 按「拖拽源类型 × 目标行类型 × 上下半区」解析出最终拖放目标。
 * 规则（与 api-x 一致）：
 * - 连接拖到连接行：上半区=插前 / 下半区=插后（同组重排，或跨文件夹移动，或移回根级）；
 * - 连接拖到文件夹行：放入该文件夹（追加末尾）；
 * - 连接拖到「根级」落点：移到根级末尾；
 * - 文件夹拖到文件夹行：上半区=插前（同级排序）/ 下半区=移入该文件夹（嵌套）。
 */
internal fun resolveDrop(
    payload: TreeDragPayload?,
    zones: Map<String, Pair<Rect, RowDropDesc>>,
    point: Offset,
): ResolvedDrop? {
    if (payload == null) return null
    val hit = zones.values
        .filter { it.first.contains(point) }
        .minByOrNull { it.first.height }
        ?: return null
    val (bounds, desc) = hit
    val topHalf = point.y < bounds.top + bounds.height / 2f
    return when (payload) {
        is TreeDragPayload.Connection -> when (desc) {
            is RowDropDesc.ConnectionDesc ->
                if (topHalf) {
                    ResolvedDrop(desc.rowKey, DropIndicator.InsertBefore, TreeDropTarget.ConnectionSlot(desc.folderId, desc.connectionIndex))
                } else {
                    ResolvedDrop(desc.rowKey, DropIndicator.InsertAfter, TreeDropTarget.ConnectionSlot(desc.folderId, desc.connectionIndex + 1))
                }
            is RowDropDesc.FolderDesc ->
                ResolvedDrop(desc.rowKey, DropIndicator.Into, TreeDropTarget.IntoFolder(desc.folderId))
            RowDropDesc.RootDesc ->
                ResolvedDrop(desc.rowKey, DropIndicator.Into, TreeDropTarget.ConnectionSlot(null, Int.MAX_VALUE))
        }
        is TreeDragPayload.Folder -> when (desc) {
            is RowDropDesc.FolderDesc ->
                if (topHalf) {
                    ResolvedDrop(desc.rowKey, DropIndicator.InsertBefore, TreeDropTarget.FolderSlot(desc.parentFolderId, desc.folderIndex))
                } else {
                    // 下半区 = 移入该文件夹（嵌套）
                    ResolvedDrop(desc.rowKey, DropIndicator.Into, TreeDropTarget.IntoFolder(desc.folderId))
                }
            else -> null
        }
    }
}

/** 拖拽时各可见行的落点区域注册表（矩形 + 描述符）。 */
class DropZoneRegistry {
    val zones = mutableStateMapOf<String, Pair<Rect, RowDropDesc>>()

    fun sync(key: String, bounds: Rect, desc: RowDropDesc) {
        zones[key] = bounds to desc
    }

    fun removeKey(key: String) {
        zones.remove(key)
    }
}

/** 仅给 pointerInput 读坐标用，不触发 Compose 重组（避免每帧 onGloballyPositioned 卡 UI）。 */
class LayoutCoordsHolder {
    var coords: LayoutCoordinates? = null
}
