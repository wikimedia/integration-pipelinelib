import groovy.mock.interceptor.MockFor
import static groovy.test.GroovyAssert.*
import groovy.util.GroovyTestCase

import java.io.FileNotFoundException

import org.wikimedia.integration.Blubber
import org.wikimedia.integration.PipelineRunner
import org.wikimedia.integration.Utility

class PipelineRunnerTest extends GroovyTestCase {
  private class WorkflowScript { // Mock for Jenkins Pipeline workflow context

  }

  private class WorkflowException extends Exception {
    public WorkflowException(message) {
      super(message)
    }
  }

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
      assert args.script ==~ (/^docker build --pull --force-rm=true --label 'foo=a' --label 'bar=b' / +
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
      [
        chart: [
          name: "exampleChart",
          version: "0.0.1",
        ]
      ]
    }

    mockWorkflow.demand.writeYaml { kwargs ->
      assert kwargs["file"] == ".pipeline/values.yaml.randomfoo"
      assert kwargs["data"] == [
        docker: [
          registry: "docker-registry.wikimedia.org",
          pull_policy: "IfNotPresent",
        ],
        main_app: [
          image: "wikimedia/foo/name",
          version: "footag",
        ],
        foo: "override",
      ]
    }

    mockWorkflow.demand.sh { cmd ->
      def expectedCmd = "helm --tiller-namespace='ci' install " +
                        "'exampleChart' " +
                        "--namespace='ci' " +
                        "--values '.pipeline/values.yaml.randomfoo' " +
                        "-n 'foo/name-randomfoo' " +
                        "--debug --wait --timeout 120 " +
                        "--repo https://helm-charts.wikimedia.org/stable/ " +
                        "--version '0.0.1'"

      assert cmd == expectedCmd
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.deploy("foo/name", "footag", [foo: "override"])
    }
  }

  void testDeploy_executesHelmWithKubeConfig() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.readYaml {
      [
        chart: [
          name: "exampleChart",
        ],
      ]
    }

    mockWorkflow.demand.writeYaml { kwargs ->
      return
    }

    mockWorkflow.demand.sh { cmd ->
      def expectedCmd = "KUBECONFIG='/etc/kubernetes/foo.config' " +
                        "helm --tiller-namespace='ci' install " +
                        "'exampleChart' " +
                        "--namespace='ci' " +
                        "--values '.pipeline/values.yaml.randomfoo' " +
                        "-n 'foo/name-randomfoo' " +
                        "--debug --wait --timeout 120 " +
                        "--repo https://helm-charts.wikimedia.org/stable/ "

      assert cmd == expectedCmd
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript(),
                                      kubeConfig: "/etc/kubernetes/foo.config")

      runner.deploy("foo/name", "footag")
    }
  }

  void testDeploy_purgesFailedRelease() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.readYaml {
      [
        chart: [
          name: "exampleChart",
        ]
      ]
    }

    mockWorkflow.demand.writeYaml { kwargs ->
      return
    }

    mockWorkflow.demand.sh { cmd ->
      def expectedCmd = "helm --tiller-namespace='ci' install " +
                        "'exampleChart' " +
                        "--namespace='ci' " +
                        "--values '.pipeline/values.yaml.randomfoo' " +
                        "-n 'foo/name-randomfoo' " +
                        "--debug --wait --timeout 120 " +
                        "--repo https://helm-charts.wikimedia.org/stable/ "

      assert cmd == expectedCmd

      throw new WorkflowException("foo");
    }

    mockWorkflow.demand.sh { cmd ->
      def expectedCmd = "helm --tiller-namespace='ci' " +
                        "delete --purge 'foo/name-randomfoo'"

      assert cmd == expectedCmd
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      shouldFail(WorkflowException) {
        runner.deploy("foo/name", "footag")
      }
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

  void testUpdateChart() {
    def mockWorkflow = new MockFor(WorkflowScript)

      def mockEnv = [
        JOB_NAME: "fooJob",
        BUILD_NUMBER: "1234",
      ]

      mockWorkflow.demand.getEnv { mockEnv }
      mockWorkflow.demand.getEnv { mockEnv }

      mockWorkflow.demand.sh { cmd ->
        assert cmd == """\
          |git checkout -b 'randomfoo'
          |./update_version/update_version.py -s 'fooChart' -v 'fooVersion' 
          |git add -A
          |git commit -m 'fooChart: pipeline bot promote' -m 'Promote fooChart to version fooVersion' -m 'Job: fooJob Build: 1234'
          |git push origin HEAD:refs/for/master%topic=pipeline-promote
          |git checkout master
          |""".stripMargin()
      }

      mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.updateChart("fooChart", "fooVersion", [])
    }
  }

  void testUpdateChart_withEnvs(){
    def mockWorkflow = new MockFor(WorkflowScript)

    def mockEnv = [
      JOB_NAME: "fooJob",
      BUILD_NUMBER: "1234",
    ]

    mockWorkflow.demand.getEnv { mockEnv }
    mockWorkflow.demand.getEnv { mockEnv }

    mockWorkflow.demand.sh { cmd ->
      assert cmd == """\
        |git checkout -b 'randomfoo'
        |./update_version/update_version.py -s 'fooChart' -v 'fooVersion' -e 'fooEnv'
        |git add -A
        |git commit -m 'fooChart: pipeline bot promote' -m 'Promote fooChart to version fooVersion' -m 'Job: fooJob Build: 1234'
        |git push origin HEAD:refs/for/master%topic=pipeline-promote
        |git checkout master
        |""".stripMargin()
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.updateChart("fooChart", "fooVersion", ["fooEnv"])
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

    mockWorkflow.demand.echo { string ->
      assert string == 'exec docker run --rm sha256:\'foo\''
    }

    mockWorkflow.demand.timeout { Map args, Closure c ->
      assert args.time == 20
      assert args.unit == "MINUTES"

      c()
    }

    mockWorkflow.demand.withCredentials { list, Closure c ->
      assert list == []
      mockWorkflow.demand.sh { cmd ->
        assert cmd == '''
          set +x
          exec docker run --rm sha256:'foo'
          set -x
        '''
      }
      c()
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.run("foo")
    }
  }

  void testRun_withEnvs() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.echo { string ->
      assert string == 'exec docker run --rm -e "foo=bar" sha256:\'foo\''
    }

    mockWorkflow.demand.timeout { Map args, Closure c ->
      assert args.time == 20
      assert args.unit == "MINUTES"

      c()
    }

    mockWorkflow.demand.withCredentials { list, Closure c ->
      assert list == []
      mockWorkflow.demand.sh { cmd ->
        assert cmd == '''
          set +x
          exec docker run --rm -e "foo=bar" sha256:'foo'
          set -x
        '''
      }
      c()
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.run("foo", [], [foo: "bar"])
    }
  }

  void testRun_withCreds() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.echo { string ->
      assert string == 'exec docker run --rm -e "SONAR_API_KEY=${SONAR_API_KEY}" sha256:\'foo\''
    }

    mockWorkflow.demand.timeout { Map args, Closure c ->
      assert args.time == 20
      assert args.unit == "MINUTES"

      c()
    }

    mockWorkflow.demand.withCredentials { list, Closure c ->
      assert list == [[$class:'StringBinding', credentialsId:'sonarid', variable:'SONAR_API_KEY']]
      mockWorkflow.demand.sh { cmd ->
        assert cmd == '''
          set +x
          exec docker run --rm -e "SONAR_API_KEY=${SONAR_API_KEY}" sha256:'foo'
          set -x
        '''
      }
      c()
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.run("foo", [], [:], [sonarid:'SONAR_API_KEY'])
    }
  }

  void testRun_withCredsAndEnvs() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.echo { string ->
      assert string == 'exec docker run --rm -e "foo=bar" -e "SONAR_API_KEY=${SONAR_API_KEY}" sha256:\'foo\''
    }

    mockWorkflow.demand.timeout { Map args, Closure c ->
      assert args.time == 20
      assert args.unit == "MINUTES"

      c()
    }

    mockWorkflow.demand.withCredentials { list, Closure c ->
      assert list == [[$class:'StringBinding', credentialsId:'sonarid', variable:'SONAR_API_KEY']]
      mockWorkflow.demand.sh { cmd ->
        assert cmd == '''
          set +x
          exec docker run --rm -e "foo=bar" -e "SONAR_API_KEY=${SONAR_API_KEY}" sha256:'foo'
          set -x
        '''
      }
      c()
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.run("foo", [], [foo: "bar"], [sonarid:'SONAR_API_KEY'])
    }
  }
}
