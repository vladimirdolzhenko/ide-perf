/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.idea.perf.vfstracer

import com.intellij.ui.components.JBTreeTable
import com.intellij.ui.treeStructure.treetable.TreeTableModel
import java.util.*
import javax.swing.JTree
import javax.swing.event.EventListenerList
import javax.swing.event.TreeModelEvent
import javax.swing.event.TreeModelListener
import javax.swing.tree.TreePath

private const val COLUMN_COUNT = 2
private const val STUB_INDEX_ACCESSES = 0
private const val PSI_ELEMENT_WRAPS = 1

private val childrenCache = WeakHashMap<VirtualFileTree, Array<VirtualFileTree>>()

private operator fun VirtualFileTree.get(index: Int): VirtualFileTree {
    val cachedChildren = childrenCache.getOrPut(this) { children.values.toTypedArray() }
    return cachedChildren[index]
}

private fun VirtualFileTree.indexOf(child: VirtualFileTree): Int {
    val cachedChildren = childrenCache.getOrPut(this) { children.values.toTypedArray() }
    return cachedChildren.indexOf(child)
}

class VfsStatTreeTableModel: TreeTableModel {
    private val tree = MutableVirtualFileTree.createRoot()
    private val listeners = EventListenerList()

    fun setStats(newStats: VirtualFileTree) {
        val treeDiff = VirtualFileTreeDiff.create(tree, newStats)
        val listenerList = listeners.listenerList

        treeDiff.applyPatch(object: TreePatchEventListener {
            override fun onTreeInsert(
                path: VirtualFileTreePath,
                parent: MutableVirtualFileTree,
                child: MutableVirtualFileTree
            ) {
                parent.children[child.name] = child
                childrenCache.remove(parent)

                val treePath = TreePath(path.parts)
                val event = TreeModelEvent(this, treePath)
                forEachListener { it.treeStructureChanged(event) }
            }

            override fun onTreeModify(
                path: VirtualFileTreePath,
                parent: MutableVirtualFileTree,
                child: MutableVirtualFileTree,
                newChild: VirtualFileTree
            ) {
                child.stubIndexAccesses = newChild.stubIndexAccesses
                child.psiElementWraps = newChild.psiElementWraps
                childrenCache.remove(parent)

                val treePath = TreePath(path.parts)
                val indexes = intArrayOf(parent.indexOf(child))
                val children = arrayOf(child)
                val event = TreeModelEvent(this, treePath, indexes, children)
                forEachListener { it.treeNodesChanged(event) }
            }

            override fun onTreeRemove(
                path: VirtualFileTreePath,
                parent: MutableVirtualFileTree,
                child: MutableVirtualFileTree
            ) {
                val treePath = TreePath(path.parts)
                val indexes = intArrayOf(parent.indexOf(child))
                val children = arrayOf(child)
                val event = TreeModelEvent(this, treePath, indexes, children)

                parent.children.remove(child.name)
                childrenCache.remove(parent)

                forEachListener { it.treeNodesRemoved(event) }
            }

            private fun forEachListener(action: (TreeModelListener) -> Unit) {
                for (i in listenerList.size - 2 downTo 0 step 2) {
                    if (listenerList[i] == TreeModelListener::class.java) {
                        action(listenerList[i + 1] as TreeModelListener)
                    }
                }
            }
        })
    }

    override fun getColumnCount(): Int = COLUMN_COUNT

    override fun getColumnName(column: Int): String = when (column) {
        STUB_INDEX_ACCESSES -> "stub index accesses"
        PSI_ELEMENT_WRAPS -> "psi parses"
        else -> error(column)
    }

    override fun getColumnClass(column: Int): Class<*> = when (column) {
        STUB_INDEX_ACCESSES, PSI_ELEMENT_WRAPS -> java.lang.Integer::class.java
        else -> error(column)
    }

    override fun getValueAt(node: Any?, column: Int): Any {
        check(node is VirtualFileTree)
        return when (column) {
            STUB_INDEX_ACCESSES -> node.stubIndexAccesses
            PSI_ELEMENT_WRAPS -> node.psiElementWraps
            else -> error(column)
        }
    }

    override fun isCellEditable(node: Any?, column: Int): Boolean = false
    override fun setValueAt(aValue: Any?, node: Any?, column: Int) = error("Model is not editable")
    override fun setTree(tree: JTree?) {}

    override fun getRoot(): Any = tree

    override fun getChild(parent: Any?, index: Int): Any {
        check(parent is VirtualFileTree)
        return parent[index]
    }

    override fun getChildCount(parent: Any?): Int {
        check(parent is VirtualFileTree)
        return parent.children.size
    }

    override fun isLeaf(node: Any?): Boolean {
        check(node is VirtualFileTree)
        return node.isFile
    }

    override fun valueForPathChanged(path: TreePath?, newValue: Any?) {}

    override fun getIndexOfChild(parent: Any?, child: Any?): Int {
        if (parent == null || child == null) {
            return -1
        }
        check(parent is VirtualFileTree)
        check(child is VirtualFileTree)
        return parent.indexOf(child)
    }

    override fun addTreeModelListener(l: TreeModelListener?) {
        listeners.add(TreeModelListener::class.java, l)
    }

    override fun removeTreeModelListener(l: TreeModelListener?) {
        listeners.remove(TreeModelListener::class.java, l)
    }
}

class VfsStatTreeTable(private val model: VfsStatTreeTableModel): JBTreeTable(model) {
    fun setStats(newStats: VirtualFileTree) {
        model.setStats(newStats)
    }
}
