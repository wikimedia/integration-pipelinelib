import groovy.mock.interceptor.MockFor
import static groovy.test.GroovyAssert.*
import groovy.util.GroovyTestCase

import java.io.FileNotFoundException
import java.nio.file.Files

import org.wikimedia.integration.Blubber
import org.wikimedia.integration.PipelineRunner

class PipelineRunnerTest extends GroovyTestCase {
  private class WorkflowScript {} // Mock for Jenkins Pipeline workflow context

  void testConstructor_workflowScript() {
    new PipelineRunner(new WorkflowScript())
  }

  void testConstructor_workflowScriptAndProperties() {
    new PipelineRunner(new WorkflowScript(), configPath: "foo")
  }

  void testGetConfigFile() {
    def pipeline = new PipelineRunner(new WorkflowScript(), configPath: "foo")

    assert pipeline.getConfigFile("bar") == "foo/bar"
  }

  void testQualifyRegistryPath() {
    def pipeline = new PipelineRunner(new WorkflowScript())

    def url = pipeline.qualifyRegistryPath("foo")

    assert url == "docker-registry.wikimedia.org/wikimedia/foo"
  }

  void testQualifyRegistryPath_disallowsSlashes() {
    def pipeline = new PipelineRunner(new WorkflowScript())

    shouldFail(AssertionError) {
      pipeline.qualifyRegistryPath("foo/bar")
    }
  }

  void testBuild_checksWhetherConfigExists() {
    def runner = new PipelineRunner(new WorkflowScript(), blubberConfig: "nonexistent/blubber.yaml")

    shouldFail(FileNotFoundException) {
      runner.build("foo", ["bar=baz"])
    }
  }

  void testBuild_delegatesToBlubber() {
    def runner = new PipelineRunner(new WorkflowScript())

    def filesMock = new MockFor(Files)
    def blubberMock = new MockFor(Blubber)

    filesMock.demand.exists { true }
    blubberMock.demand.build { variant, labels ->
      assert variant == "foo"
      assert labels == ["bar=baz"]

      "fooimageID"
    }

    blubberMock.use {
      filesMock.use {
        runner.build("foo", ["bar=baz"])
      }
    }
  }
}
