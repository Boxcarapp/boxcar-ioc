package com.boxcar.ioc.processor;

/*-
 * #%L
 * Boxcar IoC :: Processor
 * %%
 * Copyright (C) 2026 Boxcar
 * %%
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * #L%
 */

import com.boxcar.ioc.processor.BeanModel.Dependency;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Depth-first search for cycles in a dependency graph.
 *
 * <p>A cycle is reported once, as the list of beans along it, closed by repeating the first bean.
 * Standard three-colour DFS: hitting a grey (in-progress) node from the current path is a back edge,
 * and the path segment from that node to the current one is the cycle.
 *
 * <p>{@link #components} groups the beans of a graph into its strongly connected components, which
 * is what the generator needs: the beans of a component depend on each other and have to be created
 * together.
 */
final class CycleDetector {

    private enum Colour {
        WHITE, GREY, BLACK
    }

    private final Map<BeanModel, List<BeanModel>> edges;
    private final Map<BeanModel, Colour> colours = new HashMap<>();
    private final List<BeanModel> path = new ArrayList<>();
    private final List<List<BeanModel>> cycles = new ArrayList<>();

    private CycleDetector(Map<BeanModel, List<BeanModel>> edges) {
        this.edges = edges;
    }

    /**
     * The dependency graph of the given beans: each bean mapped to the beans its dependencies were
     * resolved to, in declaration order. {@code Provider} dependencies are lazy and create no edge.
     *
     * @param constructorsOnly whether to consider constructor parameters only, or all injection points
     */
    static Map<BeanModel, List<BeanModel>> edges(Collection<BeanModel> beans, boolean constructorsOnly) {
        Map<BeanModel, List<BeanModel>> edges = new LinkedHashMap<>();
        for (BeanModel bean : beans) {
            List<BeanModel> targets = new ArrayList<>();
            List<Dependency> dependencies = constructorsOnly ? bean.constructor.parameters() : bean.dependencies();
            for (Dependency dependency : dependencies) {
                if (dependency.resolved != null && !dependency.provider) {
                    targets.add(dependency.resolved);
                }
            }
            edges.put(bean, targets);
        }
        return edges;
    }

    static List<List<BeanModel>> findCycles(Map<BeanModel, List<BeanModel>> edges) {
        CycleDetector detector = new CycleDetector(edges);
        for (BeanModel bean : edges.keySet()) {
            if (detector.colour(bean) == Colour.WHITE) {
                detector.visit(bean);
            }
        }
        return detector.cycles;
    }

    private void visit(BeanModel bean) {
        colours.put(bean, Colour.GREY);
        path.add(bean);
        for (BeanModel next : edges.getOrDefault(bean, List.of())) {
            switch (colour(next)) {
              case GREY -> {
                  List<BeanModel> cycle = new ArrayList<>(path.subList(path.indexOf(next), path.size()));
                  cycle.add(next);
                  cycles.add(cycle);
              }
              case WHITE -> visit(next);
              default -> {
                  // BLACK: already fully explored; any cycle through it has been reported.
              }
            }
        }
        path.remove(path.size() - 1);
        colours.put(bean, Colour.BLACK);
    }

    private Colour colour(BeanModel bean) {
        return colours.getOrDefault(bean, Colour.WHITE);
    }

    static String describe(List<BeanModel> cycle) {
        StringJoiner description = new StringJoiner(" -> ");
        for (BeanModel bean : cycle) {
            description.add(bean.qualifiedName);
        }
        return description.toString();
    }

    /**
     * The strongly connected components of the graph that have more than one bean, each in the order
     * the graph lists its beans. A bean that depends only on itself is not a component of its own:
     * its factory can hand the instance to itself.
     */
    static List<List<BeanModel>> components(Map<BeanModel, List<BeanModel>> edges) {
        Components components = new Components(edges);
        for (BeanModel bean : edges.keySet()) {
            if (!components.index.containsKey(bean)) {
                components.visit(bean);
            }
        }
        List<List<BeanModel>> result = new ArrayList<>();
        for (List<BeanModel> component : components.found) {
            if (component.size() > 1) {
                List<BeanModel> ordered = new ArrayList<>();
                for (BeanModel bean : edges.keySet()) {
                    if (component.contains(bean)) {
                        ordered.add(bean);
                    }
                }
                result.add(ordered);
            }
        }
        return result;
    }

    /** Tarjan's algorithm: a component is complete when its root is popped from the stack. */
    private static final class Components {

        private final Map<BeanModel, List<BeanModel>> edges;
        private final Map<BeanModel, Integer> index = new HashMap<>();
        private final Map<BeanModel, Integer> lowLink = new HashMap<>();
        private final Deque<BeanModel> stack = new ArrayDeque<>();
        private final List<List<BeanModel>> found = new ArrayList<>();
        private int next;

        Components(Map<BeanModel, List<BeanModel>> edges) {
            this.edges = edges;
        }

        void visit(BeanModel bean) {
            index.put(bean, next);
            lowLink.put(bean, next);
            next++;
            stack.push(bean);
            for (BeanModel target : edges.getOrDefault(bean, List.of())) {
                if (!index.containsKey(target)) {
                    visit(target);
                    lowLink.put(bean, Math.min(lowLink.get(bean), lowLink.get(target)));
                } else if (stack.contains(target)) {
                    lowLink.put(bean, Math.min(lowLink.get(bean), index.get(target)));
                }
            }
            if (lowLink.get(bean).equals(index.get(bean))) {
                List<BeanModel> component = new ArrayList<>();
                BeanModel member;
                do {
                    member = stack.pop();
                    component.add(member);
                } while (member != bean);
                found.add(component);
            }
        }
    }
}
