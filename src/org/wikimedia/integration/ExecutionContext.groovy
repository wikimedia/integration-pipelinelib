package org.wikimedia.integration

import com.cloudbees.groovy.cps.NonCPS
import org.codehaus.groovy.GroovyException

import org.wikimedia.integration.ExecutionGraph

/**
 * Provides execution context and value bindings to graph nodes. Each node's
 * context (see {@link ofNode()}) provides methods for saving its own values
 * and bindings for accessing values saved by ancestor nodes.
 *
 * These contexts can be used during graph stack evaluation to safely pass
 * values from ancestor nodes to descendent nodes, and to keep nodes from
 * different branches of execution to access each other's values.
 */
class ExecutionContext implements Serializable {
  private Map globals = [:]
  private ExecutionGraph graph

  ExecutionContext(executionGraph) {
    graph = executionGraph
  }

  /**
   * Create and return an execution context for the given node.
   */
  NodeContext ofNode(node) {
    new NodeContext(node, graph.ancestorsOf(node))
  }

  /**
   * Returns the names of all values bound by node contexts.
   */
  List getAllKeys() {
    def keys = []

    for (def ns in globals) {
      for (def key in globals[ns]) {
        keys.add("${ns}.${key}")
      }
    }

    keys
  }

  /**
   * Provides an execution context for a single given node that can resolve
   * bindings for values stored by ancestor nodes, set its own values, and
   * safely interpolate user-provided strings.
   *
   * @example
   * Given a graph:
   * <pre><code>
   *   def context = new ExecutionContext(new ExecutionGraph([
   *     ["a", "b", "c", "d", "e", "f"],
   *     ["x", "d", "y", "f"],
   *   ]))
   * </code></pre>
   *
   * @example
   * Values can be bound to any existing node.
   * <pre><code>
   *   context.ofNode("a")["foo"] = "afoo"
   *   context.ofNode("a")["bar"] = "abar"
   *   context.ofNode("b")["foo"] = "bfoo"
   *   context.ofNode("x")["bar"] = "xbar"
   * </code></pre>
   *
   * @example
   * Those same values can be accessed in contexts of descendent nodes, but
   * not in contexts of unrelated nodes.
   * <pre><code>
   *   assert context.ofNode("c").binding("a", "foo") == "afoo"
   *   assert context.ofNode("c").binding("a", "bar") == "abar"
   *   assert context.ofNode("c").binding("b", "foo") == "bfoo"
   *
   *   assert context.ofNode("c").binding("x", "bar") == null
   *
   *   assert context.ofNode("e").binding("a", "foo") == "afoo"
   *   assert context.ofNode("e").binding("a", "bar") == "abar"
   *   assert context.ofNode("e").binding("b", "foo") == "bfoo"
   *   assert context.ofNode("e").binding("x", "bar") == "xbar"
   * </code></pre>
   *
   * @example
   * Leveraging all of the above, user-provided configuration can be safely
   * interpolated.
   * <pre><code>
   *   assert (context.ofNode("c") % 'w-t-${a.foo} ${b.foo}') == "w-t-afoo bfoo"
   *   assert (context.ofNode("c") % 'w-t-${a.bar}') == "w-t-abar"
   *   assert (context.ofNode("x") % 'w-t-${x.bar}') == 'w-t-${x.bar}'
   * </code></pre>
   */
  class NodeContext implements Serializable {
    final VAR_EXPRESSION = /\$\{\w*\.\w+\}/

    final def node
    final Set ancestors

    NodeContext(contextNode, contextAncestors) {
      globals[contextNode] = globals[contextNode] ?: [:]

      node = contextNode
      ancestors = contextAncestors
    }

    /**
     * Binds a value to a name in the globals store under the node's
     * namespace. The value may later be retrieved by any descendent node's
     * context using {@link binding()}.
     */
    @NonCPS
    void bind(String key, value)
      throws NameAlreadyBoundException {

      if (globals[node].containsKey(key)) {
        throw new NameAlreadyBoundException(key: key)
      }

      globals[node][key] = value
    }

    /**
     * Retrieves a value previously bound using {@link bind()} to this node's
     * context. If the given key is not found under this node's namespace, a
     * {@link NameNotFoundException} is thrown.
     */
    def binding(String key)
      throws NameNotFoundException {

      if (!globals[node].containsKey(key)) {
        throw new NameNotFoundException(ns: node, key: key)
      }

      globals[node][key]
    }

    /**
     * Retrieves a value previously bound using {@link bind()} under the given
     * ancestor node's namespace and name.
     */
    def binding(ancestorNode, String key)
      throws NameNotFoundException, AncestorNotFoundException {

      if (!(ancestorNode in ancestors)) {
        throw new AncestorNotFoundException(ancestor: ancestorNode, node: node)
      }

      if (!globals[ancestorNode].containsKey(key)) {
        throw new NameNotFoundException(ns: ancestorNode, key: key)
      }

      globals[ancestorNode][key]
    }

    /**
     * Returns all objects bound to the given name under any node namespace,
     * as well as the node under which it is found.
     *
     * This should only be used at the end of an execution graph.
     */
    Map getAll(String key) {
      globals.findAll { it.value[key] != null }.collectEntries { [it.key, it.value[key]] }
    }

    /**
     * Operator alias for {@link binding(String)} or, if a "namespace.key" is
     * given, {@link binding(def, String)}.
     */
    def getAt(String key) {
      def keys = key.split(/\./)

      if (keys.size() > 1) {
        if (keys[0] == "") {
          return binding(keys[1])
        }

        return binding(keys[0], keys[1])
      }

      return binding(key)
    }

    /**
     * Interpolates the given string by substituting all symbol expressions
     * with values previously bound by ancestor nodes.
     */
    String interpolate(String str) {
      // NOTE call to replaceAll does not rely on its sub matching feature as
      // Groovy CPS does not implement it correctly, and marking this method
      // as NonCPS causes it to only ever return the first substitution.
      str.replaceAll(VAR_EXPRESSION) {
        this[it[2..-2]]
      }
    }

    /**
     * Operator alias for {@link interpolate()}.
     */
    String mod(String str) {
      interpolate(str)
    }

    /**
     * Operator alias for {@link bind()}.
     */
    void putAt(String key, value) {
      bind(key, value)
    }
  }

  class AncestorNotFoundException extends GroovyException {
    def ancestor, node

    String getMessage() {
      "cannot access '${ancestor}.*' values since '${node}' does not follow it in the graph '${graph}'"
    }
  }

  class NameNotFoundException extends GroovyException {
    def ns, key

    String getMessage() {
      "no value bound for '${ns}.${key}'; all bound names are: ${getAllKeys().join(", ")}"
    }
  }

  class NameAlreadyBoundException extends GroovyException {
    def key

    String getMessage() {
      "'${node}' already has a value assigned to '${key}'"
    }
  }
}
