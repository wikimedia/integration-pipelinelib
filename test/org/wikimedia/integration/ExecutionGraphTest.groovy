import groovy.mock.interceptor.MockFor
import static groovy.test.GroovyAssert.*
import groovy.util.GroovyTestCase

import org.wikimedia.integration.ExecutionGraph

class ExecutionGraphTest extends GroovyTestCase {
  void testAncestorsOf() {
    def graph = new ExecutionGraph([
      ["a", "b", "c", "d", "e", "f"],
      ["x", "d", "y", "f"],
    ])

    assert graph.ancestorsOf("c") == ["b", "a"] as Set
    assert graph.ancestorsOf("d") == ["c", "b", "a", "x"] as Set
    assert graph.ancestorsOf("f") == ["e", "d", "c", "b", "a", "x", "y"] as Set
  }

  void testLeaves() {
    def graph = new ExecutionGraph([
      ["a", "b", "c", "d"],
      ["f", "b", "g"],
    ])

    assert graph.leaves() == ["g", "d"] as Set
  }

  void testInDegreeOf() {
    def graph = new ExecutionGraph([
      ["a", "b", "c"],
      ["d", "b", "e"],
      ["f", "b"],
    ])

    assert graph.inDegreeOf("a") == 0
    assert graph.inDegreeOf("d") == 0
    assert graph.inDegreeOf("f") == 0
    assert graph.inDegreeOf("b") == 3
    assert graph.inDegreeOf("e") == 1
    assert graph.inDegreeOf("c") == 1
  }

  void testInTo() {
    def graph = new ExecutionGraph([
      ["a", "b", "c"],
      ["d", "b", "e"],
      ["f", "b"],
    ])

    assert graph.inTo("a") == [] as Set
    assert graph.inTo("d") == [] as Set
    assert graph.inTo("f") == [] as Set
    assert graph.inTo("b") == ["a", "d", "f"] as Set
    assert graph.inTo("c") == ["b"] as Set
    assert graph.inTo("e") == ["b"] as Set
  }

  void testNodes() {
    def graph = new ExecutionGraph([
      ["a", "b", "c"],
      ["x", "b", "y"],
      ["z"],
    ])

    assert graph.nodes() == ["a", "b", "c", "x", "y", "z"] as Set
  }

  void testOr() {
    def graph1 = new ExecutionGraph([
      ["x", "y", "z"],
    ])

    def graph2 = new ExecutionGraph([
      ["a", "b", "c"],
      ["d", "b", "e"],
    ])

    assert (graph1 | graph2) == new ExecutionGraph([
      ["x", "y", "z"],
      ["a", "b", "c"],
      ["d", "b", "e"],
    ])
  }

  void testOutDegreeOf() {
    def graph = new ExecutionGraph([
      ["a", "b", "c"],
      ["d", "b", "e"],
      ["f", "b"],
    ])

    assert graph.outDegreeOf("a") == 1
    assert graph.outDegreeOf("d") == 1
    assert graph.outDegreeOf("f") == 1
    assert graph.outDegreeOf("b") == 2
    assert graph.outDegreeOf("e") == 0
    assert graph.outDegreeOf("c") == 0
  }

  void testOutOf() {
    def graph = new ExecutionGraph([
      ["a", "b", "c"],
      ["d", "b", "e"],
      ["f", "b"],
    ])

    assert graph.outOf("a") == ["b"] as Set
    assert graph.outOf("d") == ["b"] as Set
    assert graph.outOf("f") == ["b"] as Set
    assert graph.outOf("b") == ["c", "e"] as Set
    assert graph.outOf("c") == [] as Set
    assert graph.outOf("e") == [] as Set
  }

  void testPlus() {
    def graph1 = new ExecutionGraph([
      ["x", "y", "z"],
    ])

    def graph2 = new ExecutionGraph([
      ["a", "b", "c"],
      ["d", "b", "e"],
    ])

    assert (graph1 + graph2) == new ExecutionGraph([
      ["x", "y", "z", "a", "b", "c"],
      ["x", "y", "z", "d", "b", "e"],
    ])
  }

  void testRoots() {
    def graph = new ExecutionGraph([
      ["a", "b", "c", "d", "e"],
      ["f", "b", "g", "e"],
    ])

    assert graph.roots() == ["a", "f"] as Set
  }

  void testRootsOfIsolates() {
    def graph = new ExecutionGraph([["a"], ["z"]])

    assert graph.roots() == ["a", "z"] as Set
  }

  void testStack() {
    def graph = new ExecutionGraph([
      ["a", "b", "c", "d", "e", "f"],
      ["x", "d", "y", "f"],
    ])

    assert graph.stack() == [
      ["a", "x"],
      ["b"],
      ["c"],
      ["d"],
      ["e", "y"],
      ["f"],
    ]
  }

  void testStack_cycleDetection() {
    def graph = new ExecutionGraph([
      ["a", "b", "c"],
      ["x", "b", "y", "a"],
    ])

    shouldFail(Exception) {
      graph.stack()
    }
  }

  void testToString() {
    def graph = new ExecutionGraph([["a", "b", "c"], ["x"]])

    assert graph.toString() == "digraph { a -> b; b -> c; x }"
  }
}
