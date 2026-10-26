package com.aiproject.ai.common.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部门树工具类的健壮性测试。
 *
 * <p>针对两类曾经真实存在的问题：</p>
 * <ol>
 *   <li>自引用父节点（parentsno == unitsno）会把自己挂进自己的 children，
 *       JSON 序列化时无限递归直到 StackOverflow——部门数据是脏数据时整个
 *       部门管理页直接打不开；</li>
 *   <li>{@code Collectors.toMap} 没有 merge 函数，遇到重复 code 抛
 *       IllegalStateException；code 为 null 时抛 NPE。</li>
 * </ol>
 */
class ListToTreeUtilTest {

    /** 测试用的部门节点 */
    static class Node {
        final String unitsno;
        final String parentsno;
        final List<Node> children = new ArrayList<Node>();

        Node(String unitsno, String parentsno) {
            this.unitsno = unitsno;
            this.parentsno = parentsno;
        }

        @Override
        public String toString() {
            return unitsno;
        }
    }

    private List<Node> tree(List<Node> list) {
        return new ListToTreeUtil<Node>()
                .parent(n -> n.parentsno)
                .code(n -> n.unitsno)
                .children(n -> n.children)
                .tree(list);
    }

    /** 正常两级树：顶级 + 子级挂对位置 */
    @Test
    void shouldBuildTwoLevelTree() {
        Node root = new Node("1000", "0");
        Node child = new Node("10000001", "1000");

        List<Node> result = tree(Arrays.asList(root, child));

        assertEquals(1, result.size(), "只有一个顶级节点");
        assertSame(root, result.get(0));
        assertEquals(1, root.children.size());
        assertSame(child, root.children.get(0));
    }

    /**
     * 自引用父节点：不能把自己挂进自己，也不能抛异常。
     * 修复前这里会 StackOverflow。
     */
    @Test
    void selfReferencingParent_shouldNotStackOverflow() {
        Node selfRef = new Node("1000", "1000");

        List<Node> result = tree(Arrays.asList(selfRef));

        assertEquals(1, result.size(), "自引用节点应作为顶级节点");
        assertTrue(selfRef.children.isEmpty(), "绝不能把自己挂进自己的 children");
    }

    /** 重复 code：不能抛 IllegalStateException，保留先出现的那条 */
    @Test
    void duplicateCode_shouldNotThrow() {
        Node first = new Node("1000", "0");
        Node second = new Node("1000", "0");
        Node child = new Node("10000001", "1000");

        List<Node> result = tree(Arrays.asList(first, second, child));

        assertEquals(2, result.size(), "两条重复 code 都作为顶级节点出现");
        // 子节点挂到先出现的那条上
        assertEquals(1, first.children.size());
        assertTrue(second.children.isEmpty());
    }

    /** code 为 null：不能抛 NPE，按顶级节点处理 */
    @Test
    void nullCode_shouldNotThrow() {
        Node nullCode = new Node(null, "0");
        Node normal = new Node("1000", "0");

        List<Node> result = tree(Arrays.asList(nullCode, normal));

        assertEquals(2, result.size());
    }

    /** 父节点不在集合内：按顶级节点处理，不抛异常 */
    @Test
    void orphanNode_shouldBecomeRoot() {
        Node orphan = new Node("10000001", "9999");

        List<Node> result = tree(Arrays.asList(orphan));

        assertEquals(1, result.size());
        assertSame(orphan, result.get(0));
    }

    /** 空集合：返回空列表，不抛异常 */
    @Test
    void emptyList_shouldReturnEmpty() {
        assertTrue(tree(new ArrayList<Node>()).isEmpty());
    }

    /** 指定父节点的重载：父节点为 null 的对象不能导致 NPE */
    @Test
    void treeWithPid_shouldNotThrowOnOrphan() {
        Node orphan = new Node("10000001", "9999");
        Node underPid = new Node("10000002", "1000");

        List<Node> result = new ListToTreeUtil<Node>()
                .parent(n -> n.parentsno)
                .code(n -> n.unitsno)
                .children(n -> n.children)
                .tree(Arrays.asList(orphan, underPid), "1000");

        assertEquals(1, result.size());
        assertSame(underPid, result.get(0));
    }
}
