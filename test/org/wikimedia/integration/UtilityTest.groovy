import groovy.util.GroovyTestCase

import static org.wikimedia.integration.Utility.*

class UtilityTestCase extends GroovyTestCase {
  void testArg() {
    assert arg("foo bar'\n baz") == """'foo bar'\\''\n baz'"""
  }

  void testArgs() {
    assert args(["foo bar'\n baz", "qux"]) == """'foo bar'\\''\n baz' 'qux'"""
  }

  void testCollectAllNested() {
    def obj = [
      foo: "cat",
      bar: [
        [$class: "Bar", baz: "dog"],
        [$class: "Qux", wat: "goat"],
      ],
    ]

    def result = collectAllNested(obj, {
      if (it instanceof Map && it['$class'] == "Qux") {
        it['wat'] = "lamb"
      }

      it
    })

    assert result == [
      foo: "cat",
      bar: [
        [$class: "Bar", baz: "dog"],
        [$class: "Qux", wat: "lamb"],
      ],
    ]
  }

  void testEnvs() {
      assert envs([foo: "'FOO_KEY'", bar: "baz"]) == """-e "foo='FOO_KEY'" -e "bar=baz" """
  }

  void testMapStrings() {
    def map = [
      foo: [
        bar: [
          baz: ["cat", "gnat"],
        ],
      ],
      qux: "dog",
      quux: true,
    ]

    assert mapStrings(map) { it.reverse() } == [
      foo: [
        bar: [
          baz: ["tac", "tang"],
        ],
      ],
      qux: "god",
      quux: true,
    ]
  }

  void testMerge() {
    def map1 = [
      foo: [
        bar: [
          baz: "cat",
          qux: "dog",
        ],
      ],
    ]

    def map2 = [
      foo: [
        bar: [
          qux: ["goat", "boat"]
        ]
      ],
      tree: "rocketship",
    ]

    assert merge(map1, map2) == [
      foo: [
        bar: [
          baz: "cat",
          qux: ["goat", "boat"],
        ],
      ],
      tree: "rocketship",
    ]
  }

  void testRandomAlphanum() {
    def expectedChars = ('a'..'z') + ('0'..'9')
    def alphanum = randomAlphanum(12)

    assert alphanum.length() == 12
    alphanum.each { assert it in expectedChars }
  }

  void testSanitizeRelativePath() {
    assert sanitizeRelativePath("../../foo/../bar") == "bar"
    assert sanitizeRelativePath("/../../foo/../bar") == "bar"
  }
}
