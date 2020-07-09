import groovy.util.GroovyTestCase

import static org.wikimedia.integration.Utility.*

class UtilityTestCase extends GroovyTestCase {
  void testArg() {
    assert arg("foo bar'\n baz") == """'foo bar'\\''\n baz'"""
  }

  void testArgs() {
    assert args(["foo bar'\n baz", "qux"]) == """'foo bar'\\''\n baz' 'qux'"""
  }

  void testEnvs() {
      assert envs([foo: "'FOO_KEY'", bar: "baz"]) == "-e foo='FOO_KEY' -e bar=baz "
  }

  void testFlatten() {
    def map = [
      foo: [
        bar: [
          baz: "cat",
        ],
      ],
      qux: "dog",
    ]

    assert flatten(map) == [
      "foo.bar.baz": "cat",
      "qux": "dog",
    ]

    assert flatten(map) { it.reverse() } == [
      "foo.bar.baz": "tac",
      "qux": "god",
    ]
  }

  void testRandomAlphanum() {
    def expectedChars = ('a'..'z') + ('0'..'9')
    def alphanum = randomAlphanum(12)

    assert alphanum.length() == 12
    alphanum.each { assert it in expectedChars }
  }
}
