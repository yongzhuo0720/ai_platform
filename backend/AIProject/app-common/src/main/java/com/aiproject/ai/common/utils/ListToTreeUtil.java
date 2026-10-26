package com.aiproject.ai.common.utils;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service()
public class ListToTreeUtil<T> {

    private Function<T, Object> parent;
    private Function<T, Object> code;
    private Function<T, List<T>> children;

    public ListToTreeUtil<T> parent(Function<T, Object> parent) {
        this.parent = parent;
        return this;
    }

    public ListToTreeUtil<T> code(Function<T, Object> code) {
        this.code = code;
        return this;
    }

    public ListToTreeUtil<T> children(Function<T, List<T>> children) {
        this.children = children;
        return this;
    }

    /**
     * 数组转树形
     * 无指定父节点，则会按照当前集合查询，查询到当前集合没有找到父节点的则为父节点
     * */
    public List<T> tree(List<T> list){
        List<T> treeList = new ArrayList<>();
        Map<Object, T> codeMap = buildCodeMap(list);
        for (T t : list) {
            Object parentCode = parent.apply(t);
            // code 为 null 或父节点就是自己：都当作顶级节点。
            // 修复前用 Collectors.toMap 且无 merge 函数，遇到重复 code 直接抛
            // IllegalStateException；自引用父节点更糟——会把自己挂进自己的 children，
            // JSON 序列化时无限递归直到 StackOverflow。
            if (parentCode == null || Objects.equals(parentCode, code.apply(t))
                    || !codeMap.containsKey(parentCode)) {
                treeList.add(t);
            } else {
                children.apply(codeMap.get(parentCode)).add(t);
            }
        }
        return treeList;
    }

    /**
     * 数组转树形
     * 指定父节点，只查询指定父节点下的数据节点
     * */
    public List<T> tree(List<T> list, Object pid){
        List<T> treeList = new ArrayList<>();
        Map<Object, T> codeMap = buildCodeMap(list);
        for (T t : list) {
            if(Objects.equals(pid, parent.apply(t))){
                treeList.add(t);
            }else{
                T parentT = codeMap.get(parent.apply(t));
                // 修复前这里没判 null：pid 之外的节点若父节点不在集合内会直接 NPE
                if(Objects.nonNull(parentT) && !Objects.equals(parentT, t)){
                    children.apply(parentT).add(t);
                }
            }
        }
        return treeList;
    }

    /**
     * 构造 code -> 节点 的映射。
     *
     * <p>跳过 code 为 null 的项（{@code Collectors.toMap} 遇 null key 会 NPE），
     * 并给重复 code 提供 merge 函数（默认保留先出现的，避免 IllegalStateException）。</p>
     */
    private Map<Object, T> buildCodeMap(List<T> list) {
        Map<Object, T> map = new java.util.LinkedHashMap<Object, T>();
        if (list == null) {
            return map;
        }
        for (T t : list) {
            Object key = code.apply(t);
            if (key == null) {
                continue;
            }
            map.putIfAbsent(key, t);
        }
        return map;
    }
}
