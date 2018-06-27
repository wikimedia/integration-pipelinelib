import groovy.mock.interceptor.MockFor
import groovy.util.GroovyTestCase

import org.wikimedia.integration.Blubber

class BlubberTestCase extends GroovyTestCase {
  private class Steps {} // Mock for Jenkins Pipeline steps context

  void testBuildCommand() {
    def mock = new MockFor(Steps)

    mock.demand.sh { cmd ->
      assert cmd == "blubber 'foo/blubber.yaml' 'foo' | " +
                    "docker build --pull --tag 'foo-tag' " +
                    "--label 'foo=a' --label 'bar=b' --file - ."
    }

    mock.use {
      def blubber = new Blubber(new Steps(), "foo/blubber.yaml")

      blubber.build("foo", "foo-tag", ["foo=a", "bar=b"])
    }
  }
}
