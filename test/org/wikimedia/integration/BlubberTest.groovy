import groovy.mock.interceptor.MockFor
import groovy.util.GroovyTestCase

import org.wikimedia.integration.Blubber

class BlubberTestCase extends GroovyTestCase {
  private class WorkflowScript {} // Mock for Jenkins Pipeline workflow context

  def blubberConfig = ".pipeline/blubber.yaml"
  def blubberoidURL = "https://an.example/blubberoid/v1/"

  void testGenerateDockerfile() {
    def mock = new MockFor(WorkflowScript)
    def config = "version: v3\n" +
                 "base: foo"

    mock.demand.readFile { args ->
      assert args.file == blubberConfig

      config
    }

    mock.demand.httpRequest { args ->
      assert args.httpMode == "POST"
      assert args.customHeaders == [[name: "content-type", value: "application/yaml"]]
      assert args.requestBody == config
      assert args.consoleLogResponseBody == true
      assert args.validResponseCodes == "200"

      [content: "BASE foo\n"]
    }

    mock.use {
      def blubber = new Blubber(new WorkflowScript(), blubberConfig, blubberoidURL)
      def dockerfile = blubber.generateDockerfile("foo")

      assert dockerfile == "BASE foo\n"
    }
  }

  void testGetConfigMediaType_yaml() {
    def blubber = new Blubber(new WorkflowScript(), blubberConfig, blubberoidURL)

    assert blubber.getConfigMediaType() == "application/yaml"
  }

  void testGetConfigMediaType_yml() {
    def blubber = new Blubber(new WorkflowScript(), blubberConfig, blubberoidURL)

    assert blubber.getConfigMediaType() == "application/yaml"
  }

  void testGetConfigMediaType_json() {
    def blubber = new Blubber(new WorkflowScript(), ".pipeline/blubber.json", blubberoidURL)

    assert blubber.getConfigMediaType() == "application/json"
  }

  void testGetRequestURL() {
    def blubber = new Blubber(new WorkflowScript(), blubberConfig, blubberoidURL)

    assert blubber.getRequestURL("foo bar") == "https://an.example/blubberoid/v1/foo+bar"
  }
}
