package org.wikimedia.integration

import com.cloudbees.groovy.cps.NonCPS

/**
 * Represents a directed acyclic graph (DAG) for defining pipeline stage
 * dependencies and scheduling them in a parallel topological-sort order.
 *
 * An {@link ExecutionGraph} is constructed by passing sets of arcs (aka
 * edges/branches) that may or may not intersect on common nodes.
 *
 * @example
 * A graph such as:
 * <pre><code>
 *   a       w       z
 *     ⇘   ⇙
 *       b       x
 *     ⇙   ⇘   ⇙
 *   c       y
 *     ⇘   ⇙
 *       d
 * </code></pre>
 *
 * Can be constructed any number of ways as long as all the arcs are
 * represented in the given sets.
 *
 * <pre><code>
 * def graph = ExecutionGraph([
 *   ["a", "b", "c", "d"], // defines edges a → b, b → c, c → d
 *   ["w", "b", "y"],      // defines edges w → b, b → y
 *   ["x", "y", "d"],      // defines edges x → y, y → d
 *   ["z"],                // defines no edge but an isolate node
 * ])
 * </code></pre>
 *
 * @example
 * The same graph could be constructed this way.
 *
 * <pre><code>
 * def graph = ExecutionGraph([
 *   ["a", "b", "y"],
 *   ["w", "b", "c", "d"],
 *   ["x", "y"],
 *   ["z"],
 * ])
 * </code></pre>
 *
 * @example
 * {@link ExecutionGraph#stack()} will return concurrent "frames" of the graph
 * in a topological sort order, meaning that nodes are always traversed before
 * any of their successor nodes, and nodes of independent branches can be
 * scheduled in parallel.
 *
 * For the same example graph:
 *
 * <pre><code>
 * graph.stack().each { println it.join("|") }
 * </code></pre>
 *
 * Would output:
 *
 * <pre>
 * a|w|z
 * b|x
 * c|y
 * d
 * </pre>
 */
class ExecutionGraph implements Serializable {
  /**
   * Map of graph progression, nodes and their successor (out) nodes.
   *
   * @example
   * An example graph and its <code>progression</code>.
   *
   * <pre><code>
   *   a       w       z    [
   *     ⇘   ⇙                a:[b],    w:[b],
   *       b       x
   *     ⇙   ⇘   ⇙            b:[c, y], x:[y],
   *   c       y
   *     ⇘   ⇙                c:[d],    y:[d],
   *       d                ]
   * </code></pre>
   */
  protected Map progression

  /**
   * Map of graph recession, nodes and their predecessor (in) nodes. Allows
   * for efficient backward traversal.
   *
   * @example
   * An example graph and its <code>recession</code>.
   *
   * <pre><code>
   *   a       w       z    [
   *     ⇘   ⇙                b:[a, w],
   *       b       x
   *     ⇙   ⇘   ⇙            c:[b],    y:[b, x],
   *   c       y
   *     ⇘   ⇙                d:[c, y],
   *       d                ]
   * </code></pre>
   */
  protected Map recession

  /**
   * Set of graph isolates, nodes that are unconnected from all other nodes.
   */
  protected Set isolates

  /**
   * Constructs a directed execution graph using the given sets of edge
   * sequences (arcs).
   *
   * @example
   * See {@link ExecutionGraph} for examples.
   */
  ExecutionGraph(List arcs) {
    progression = [:]
    recession = [:]
    isolates = [] as Set

    arcs.each { addArc(it as List) }
  }

  /**
   * All ancestors of (nodes eventually leading to) the given node.
   */
  Set ancestorsOf(node) {
    def parents = inTo(node)

    parents + parents.inject([] as Set) { ancestors, parent -> ancestors + ancestorsOf(parent) }
  }

  /**
   * Whether the given graph is equal to this one.
   */
  boolean equals(ExecutionGraph other) {
    progression == other.progression && isolates == other.isolates
  }

  /**
   * Returns all nodes that have no outgoing edges.
   */
  Set leaves() {
    (recession.keySet() - progression.keySet()) + isolates
  }

  /**
   * The number of nodes that lead directly to the given one.
   */
  int inDegreeOf(node) {
    inTo(node).size()
  }

  /**
   * The nodes that lead directly to the given one.
   */
  Set inTo(node) {
    recession[node] ?: [] as Set
  }

  /**
   * All nodes in the graph.
   */
  Set nodes() {
    progression.keySet() + recession.keySet() + isolates
  }

  /**
   * Returns a union of this graph and the given one.
   */
  ExecutionGraph or(ExecutionGraph other) {
    def newGraph = new ExecutionGraph()

    [this, other].each { source ->
      source.progression.each { newGraph.addSuccession(it.key, it.value) }
      source.isolates.each { newGraph.addIsolate(it) }
    }

    newGraph
  }

  /**
   * The number of nodes the given one directly leads to.
   */
  int outDegreeOf(node) {
    outOf(node).size()
  }

  /**
   * The nodes the given one directly leads to.
   */
  Set outOf(node) {
    progression[node] ?: [] as Set
  }

  /**
   * Returns a concatenation of this graph and the given one.
   */
  ExecutionGraph plus(ExecutionGraph other) {
    def newGraph = this | other

    leaves().each { leaf ->
      newGraph.addSuccession(leaf, other.roots())
    }

    newGraph
  }

  /**
   * Returns all nodes that have no incoming edges.
   */
  Set roots() {
    (progression.keySet() - recession.keySet()) + isolates
  }

  /**
   * Returns each concurrent node "frames" of the graph in a topological sort
   * order. See {@link ExecutionGraph} for examples. A {@link RuntimeException}
   * will be thrown in the event a graph cycle is detected.
   */
  List stack() throws RuntimeException {
    def concurrentFrames = []

    def graphSize = (progression.keySet() + recession.keySet() + isolates).size()
    def traversed = [] as Set
    def prevNodes

    while (traversed.size() < graphSize) {
      def nextNodes

      if (!prevNodes) {
        nextNodes = roots()
      } else {
        nextNodes = [] as Set

        prevNodes.each { prev ->
          outOf(prev).each { outNode ->
            if ((inTo(outNode) - traversed).isEmpty()) {
              nextNodes.add(outNode)
            }
          }
        }
      }

      if (!nextNodes && traversed.size() < graphSize) {
        throw new RuntimeException("cycle detected in graph (${this})")
      }

      traversed.addAll(nextNodes)
      prevNodes = nextNodes
      concurrentFrames.add(nextNodes as List)
    }

    concurrentFrames
  }

  /**
   * A string representation of the graph compatible with <code>dot</code>.
   *
   * @example
   * Render the graph with dot
   *
   * <pre><code>
   * $ echo "[graph.toString() value]" | dot -Tsvg &gt; graph.svg
   * </code></pre>
   */
  @NonCPS
  String toString() {
    def allEdges = progression.inject([]) { edges, predecessor, successors ->
      edges + successors.collect { successor ->
        "${predecessor} -> ${successor}"
      }
    }

    'digraph { ' + (allEdges + isolates).join("; ") + ' }'
  }

  protected

  /**
   * Appends a new arc of nodes to the graph.
   *
   * @example
   * An existing graph.
   * <pre><code>
   *   a
   *     ⇘
   *       b
   *         ⇘
   *           c
   * </code></pre>
   *
   * Appended with <code>graph &lt;&lt; ["x", "b", "y", "z"]</code> becomes.
   * <pre><code>
   *   a       x
   *     ⇘   ⇙
   *       b
   *     ⇙   ⇘
   *   y       c
   *     ⇘
   *       z
   * </code></pre>
   */
  @NonCPS
  void addArc(List arc) {
    if (arc.size() == 1) {
      addIsolate(arc[0])
    } else {
      arc.eachWithIndex { node, i ->
        if (i < (arc.size() - 1)) {
          addSuccession(node, [arc[i+1]])
        }
      }
    }
  }

  @NonCPS
  void addIsolate(isolate) {
    isolates.add(isolate)
  }

  @NonCPS
  void addSuccession(predecessor, successors) {
    if (!progression.containsKey(predecessor)) {
      progression[predecessor] = [] as Set
    }

    progression[predecessor].addAll(successors)

    successors.each { successor ->
      if (!recession.containsKey(successor)) {
        recession[successor] = [] as Set
      }

      recession[successor].add(predecessor)
    }

    isolates -= (successors + predecessor)
  }
}
