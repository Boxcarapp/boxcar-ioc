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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Depth-first search for cycles in a dependency graph.
 *
 * <p>A cycle is reported once, as the list of beans along it, closed by repeating the first bean.
 * Standard three-colour DFS: hitting a grey (in-progress) node from the current path is a back edge,
 * and the path segment from that node to the current one is the cycle.
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
        return cycle.stream().map(bean -> bean.qualifiedName).collect(Collectors.joining(" -> "));
    }
}
