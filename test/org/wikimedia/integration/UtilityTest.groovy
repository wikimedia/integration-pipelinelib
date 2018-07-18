import groovy.util.GroovyTestCase

import static org.wikimedia.integration.Utility.*

class UtilityTestCase extends GroovyTestCase {
  void testArg() {
    assert arg("foo bar'\n baz") == """'foo bar'\\''\n baz'"""
  }

  void testRandomAlphanum() {
    def expectedChars = ('a'..'z') + ('0'..'9')
    def alphanum = randomAlphanum(12)

    assert alphanum.length() == 12
    alphanum.each { assert it in expectedChars }
  }
}
