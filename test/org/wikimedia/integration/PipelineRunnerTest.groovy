import groovy.mock.interceptor.MockFor
import static groovy.test.GroovyAssert.*
import groovy.util.GroovyTestCase

import java.io.FileNotFoundException

import org.wikimedia.integration.Blubber
import org.wikimedia.integration.PipelineRunner
import org.wikimedia.integration.Utility

class PipelineRunnerTest extends GroovyTestCase {
  private class WorkflowScript {} // Mock for Jenkins Pipeline workflow context

  void setUp() {
    // Mock all static calls to Utility.randomAlphanum
    Utility.metaClass.static.randomAlphanum = { "randomfoo" }
  }

  void tearDown() {
    // Reset static mocks
    Utility.metaClass = null
  }

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

  void testGetTempFile() {
    def pipeline = new PipelineRunner(new WorkflowScript(), configPath: "foo")

    assert pipeline.getTempFile("bar") ==~ /^foo\/bar[a-z0-9]+$/
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
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.fileExists { path ->
      assert path == ".pipeline/nonexistent.yaml"

      false
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript(),
                                      configPath: ".pipeline",
                                      blubberConfig: "nonexistent.yaml")

      shouldFail(FileNotFoundException) {
        runner.build("foo", [bar: "baz"])
      }
    }
  }

  void testBuild_generatesDockerfileAndBuilds() {
    def mockWorkflow = new MockFor(WorkflowScript)
    def mockBlubber = new MockFor(Blubber)

    mockWorkflow.demand.fileExists { true }

    mockBlubber.demand.generateDockerfile { variant ->
      assert variant == "foo"

      "BASE: foo\n"
    }

    mockWorkflow.demand.writeFile { args ->
      assert args.text == "BASE: foo\n"
      assert args.file ==~ /^\.pipeline\/Dockerfile\.[a-z0-9]+$/
    }

    mockWorkflow.demand.sh { args ->
      assert args.returnStdout
      assert args.script ==~ (/^docker build --pull --label 'foo=a' --label 'bar=b' / +
                            /--file '\.pipeline\/Dockerfile\.[a-z0-9]+' \.$/)

      // Mock `docker build` output to test that we correctly parse the image ID
      return "Removing intermediate container foo\n" +
             " ---> bf1e86190382\n" +
             "Successfully built bf1e86190382\n"
    }

    mockWorkflow.use {
      mockBlubber.use {
        def runner = new PipelineRunner(new WorkflowScript())

        assert runner.build("foo", [foo: "a", bar: "b"]) == "bf1e86190382"
      }
    }
  }

  void testDeploy_checksConfigForChart() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.readYaml { Map args ->
      assert args.file == ".foo/bar.yaml"

      [chart: null]
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript(),
                                      configPath: ".foo",
                                      helmConfig: "bar.yaml")

      shouldFail(AssertionError) {
        runner.deploy("foo/name", "footag")
      }
    }
  }

  void testDeploy_executesHelm() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.readYaml {
      [chart: "http://an.example/chart.tgz"]
    }

    mockWorkflow.demand.sh { cmd ->
      def expectedCmd = "helm --tiller-namespace='ci' install " +
                        "--namespace='ci' --set " +
                        "'docker.registry=docker-registry.wikimedia.org'," +
                        "'docker.pull_policy=IfNotPresent'," +
                        "'main_app.image=wikimedia/foo/name'," +
                        "'main_app.version=footag' " +
                        "-n 'foo/name-randomfoo' " +
                        "--debug --wait --timeout 120 " +
                        "'http://an.example/chart.tgz'"

      assert cmd == expectedCmd
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.deploy("foo/name", "footag")
    }
  }

  void testDeploy_executesHelmWithKubeConfig() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.readYaml {
      [chart: "http://an.example/chart.tgz"]
    }

    mockWorkflow.demand.sh { cmd ->
      def expectedCmd = "KUBECONFIG='/etc/kubernetes/foo.config' " +
                        "helm --tiller-namespace='ci' install " +
                        "--namespace='ci' --set " +
                        "'docker.registry=docker-registry.wikimedia.org'," +
                        "'docker.pull_policy=IfNotPresent'," +
                        "'main_app.image=wikimedia/foo/name'," +
                        "'main_app.version=footag' " +
                        "-n 'foo/name-randomfoo' " +
                        "--debug --wait --timeout 120 " +
                        "'http://an.example/chart.tgz'"

      assert cmd == expectedCmd
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript(),
                                      kubeConfig: "/etc/kubernetes/foo.config")

      runner.deploy("foo/name", "footag")
    }
  }

  void testPurgeRelease_executesHelm() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.sh { cmd ->
      def expectedCmd = "helm --tiller-namespace='ci' delete --purge 'foorelease'"

      assert cmd == expectedCmd
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.purgeRelease("foorelease")
    }
  }

  void testPurgeRelease_executesHelmWithKubeConfig() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.sh { cmd ->
      def expectedCmd = "KUBECONFIG='/etc/kubernetes/foo.config' " +
                        "helm --tiller-namespace='ci' delete --purge 'foorelease'"

      assert cmd == expectedCmd
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript(),
                                      kubeConfig: "/etc/kubernetes/foo.config")

      runner.purgeRelease("foorelease")
    }
  }

  void testTestRelease_executesHelm() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.sh { cmd ->
      def expectedCmd = "helm --tiller-namespace='ci' test --cleanup 'foorelease'"

      assert cmd == expectedCmd
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.testRelease("foorelease")
    }
  }

  void testTestRelease_executesHelmWithKubeConfig() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.sh { cmd ->
      def expectedCmd = "KUBECONFIG='/etc/kubernetes/foo.config' " +
                        "helm --tiller-namespace='ci' test --cleanup 'foorelease'"

      assert cmd == expectedCmd
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript(),
                                      kubeConfig: "/etc/kubernetes/foo.config")

      runner.testRelease("foorelease")
    }
  }

  void testRegisterAs() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.sh { cmd ->
      assert cmd == "docker tag 'fooID' 'internal.example/foorepo/fooname:footag' && " +
                    "sudo /usr/local/bin/docker-pusher 'internal.example/foorepo/fooname:footag'"
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript(),
                                      registryInternal: 'internal.example',
                                      repository: 'foorepo')

      runner.registerAs("fooID", "fooname", "footag")
    }
  }

  void testRegisterAs_bailsOnSlashes() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      shouldFail(AssertionError) {
        runner.registerAs("fooID", "foo/name", "footag")
      }
    }
  }

  void testRemoveImage() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.sh { cmd ->
      assert cmd == "docker rmi --force 'fooID'"
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.removeImage("fooID")
    }
  }

  void testRun() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.timeout { Map args, Closure c ->
      assert args.time == 20
      assert args.unit == "MINUTES"

      c()
    }

    mockWorkflow.demand.sh { cmd ->
      assert cmd == "exec docker run --rm sha256:'foo'"
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.run("foo")
    }
  }
}
