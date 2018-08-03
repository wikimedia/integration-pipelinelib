import groovy.mock.interceptor.MockFor
import groovy.util.GroovyTestCase

import org.wikimedia.integration.Blubber

class BlubberTestCase extends GroovyTestCase {
  private class WorkflowScript {} // Mock for Jenkins Pipeline workflow context

  void testBuildCommand() {
    def mock = new MockFor(WorkflowScript)

    mock.demand.sh { args ->
      assert args.returnStdout
      assert args.script == "blubber 'foo/blubber.yaml' 'foo' | " +
                            "docker build --pull --label 'foo=a' --label 'bar=b' --file - ."

      // Mock `docker build` output to test that we correctly parse the image ID
      return "Removing intermediate container foo\n" +
             " ---> bf1e86190382\n" +
             "Successfully built bf1e86190382\n"
    }

    mock.use {
      def blubber = new Blubber(new WorkflowScript(), "foo/blubber.yaml")
      def imageID = blubber.build("foo", [foo: "a", bar: "b"])

      assert imageID == "bf1e86190382"
    }
  }
}
