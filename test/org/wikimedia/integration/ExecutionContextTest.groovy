import groovy.mock.interceptor.MockFor
import static groovy.test.GroovyAssert.*
import groovy.util.GroovyTestCase

import org.wikimedia.integration.ExecutionGraph
import org.wikimedia.integration.ExecutionContext

class ExecutionContextTest extends GroovyTestCase {
  void testNodeContextBindings() {
    /*
     *  a
     *    ⇘
     *      b
     *        ⇘
     *          c       x
     *            ⇘   ⇙
     *              d
     *            ⇙   ⇘
     *          y       e
     *            ⇘   ⇙
     *              f
     */
    def context = new ExecutionContext(new ExecutionGraph([
      ["a", "b", "c", "d", "e", "f"],
      ["x", "d", "y", "f"],
    ]))

    def cContext = context.ofNode("c")
    def eContext = context.ofNode("e")

    context.ofNode("a").bind("foo", "afoo")
    context.ofNode("a").bind("bar", "abar")
    context.ofNode("b").bind("foo", "bfoo")
    context.ofNode("x").bind("bar", "xbar")

    assert cContext.binding("a", "foo") == "afoo"
    assert cContext.binding("a", "bar") == "abar"
    assert cContext.binding("b", "foo") == "bfoo"

    shouldFail(ExecutionContext.AncestorNotFoundException) {
      cContext.binding("x", "bar")
    }

    shouldFail(ExecutionContext.NameNotFoundException) {
      cContext.binding("baz")
    }

    assert eContext.binding("a", "foo") == "afoo"
    assert eContext.binding("a", "bar") == "abar"
    assert eContext.binding("b", "foo") == "bfoo"
    assert eContext.binding("x", "bar") == "xbar"
  }

  void testNodeContextBindings_immutable() {
    /*
     *  a
     *    ⇘
     *      b
     *        ⇘
     *          c
     */
    def context = new ExecutionContext(new ExecutionGraph([
      ["a", "b", "c"],
    ]))

    context.ofNode("b").bind("foo", "bfoo")

    shouldFail(ExecutionContext.NameAlreadyBoundException) {
      context.ofNode("b").bind("foo", "newfoo")
    }

    assert context.ofNode("b").binding("foo") == "bfoo"
  }

  void testStringInterpolation() {
    /*
     *  a
     *    ⇘
     *      b
     *        ⇘
     *          c       x
     *            ⇘   ⇙
     *              d
     *            ⇙   ⇘
     *          y       e
     *            ⇘   ⇙
     *              f
     */
    def context = new ExecutionContext(new ExecutionGraph([
      ["a", "b", "c", "d", "e", "f"],
      ["x", "d", "y", "f"],
    ]))

    context.ofNode("a").bind("foo", "afoo")
    context.ofNode("a").bind("bar", "abar")
    context.ofNode("b").bind("foo", "bfoo")
    context.ofNode("x").bind("bar", "xbar")

    assert (context.ofNode("b") % 'w-t-${a.foo} ${.foo}') == "w-t-afoo bfoo"

    assert (context.ofNode("c") % 'w-t-${a.foo} ${b.foo}') == "w-t-afoo bfoo"
    assert (context.ofNode("c") % 'w-t-${a.bar}') == "w-t-abar"

    shouldFail(ExecutionContext.AncestorNotFoundException) {
      context.ofNode("x") % 'w-t-${b.bar}'
    }

    shouldFail(ExecutionContext.NameNotFoundException) {
      context.ofNode("b") % 'w-t-${.bar}'
    }
  }

  void testStringInterpolation_selfScope() {
    /*
     *  a
     *    ⇘
     *      b
     *        ⇘
     *          c
     */
    def context = new ExecutionContext(new ExecutionGraph([
      ["a", "b", "c"],
    ]))

    context.ofNode("c").bind("foo", "cfoo")

    assert (context.ofNode("c") % 'w-t-${.foo}') == "w-t-cfoo"
  }

  void testNodeContextGetAll() {
    def context = new ExecutionContext(new ExecutionGraph([
      ["a", "b", "c", "z"],
      ["x", "b", "y", "z"],
    ]))

    context.ofNode("a").bind("foo", "afoo")
    context.ofNode("c").bind("foo", "cfoo")
    context.ofNode("y").bind("foo", "yfoo")

    assert context.ofNode("z").getAll("foo") == ["afoo", "cfoo", "yfoo"]
  }

  void testNodeContextGetAt() {
    def context = new ExecutionContext(new ExecutionGraph([
      ["a", "b", "c"],
    ]))

    context.ofNode("a").bind("foo", "afoo")
    context.ofNode("c").bind("foo", "cfoo")

    assert context.ofNode("c")["a.foo"] == "afoo"
    assert context.ofNode("c")["foo"] == "cfoo"
  }

  void testNodeContextGetAt_selfScope() {
    /*
     *  a
     *    ⇘
     *      b
     *        ⇘
     *          c
     */
    def context = new ExecutionContext(new ExecutionGraph([
      ["a", "b", "c"],
    ]))

    def bContext = context.ofNode("b")

    bContext["foo"] = "bfoo"

    assert bContext[".foo"] == "bfoo"
  }
}