import groovy.mock.interceptor.MockFor
import static groovy.test.GroovyAssert.*
import groovy.util.GroovyTestCase

import java.io.FileNotFoundException
import java.net.URI

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

  void testQualifyRegistryPath_disallowsUpperCase() {
    def pipeline = new PipelineRunner(new WorkflowScript())

    shouldFail(AssertionError) {
      pipeline.qualifyRegistryPath("foo-Bar")
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

    mockBlubber.demand.generateDockerfile { variant ->
      assert variant == "foo"

      "BASE: foo\n"
    }

    mockWorkflow.demand.with {
      fileExists { true }

      writeFile { args ->
        assert args.text == "BASE: foo\n"
        assert args.file ==~ /^\.pipeline\/Dockerfile\.[a-z0-9]+$/
      }

      dir { context, Closure c ->
        assert context == "foo/dir"
        c()
      }

      fileExists { path -> false }

      writeFile { args ->
        assert args.text == ".git\n" +
                            "*.md\n" +
                            "!README.md\n"
        assert args.file == ".dockerignore"
      }

      sh { script ->
        assert script ==~ (/^docker build --pull --force-rm=true --label 'foo=a' --label 'bar=b' / +
                           /--iidfile '.pipeline\/docker.iid.randomfoo' / +
                           /--file '\.pipeline\/Dockerfile\.[a-z0-9]+' 'foo\/dir'/)
      }

      readFile { path ->
        assert '.pipeline/docker.iid.randomfoo'

        return "sha256:bf1e86190382"
      }

      sh { script ->
        assert script == "rm -f '.pipeline/docker.iid.randomfoo'"
      }
    }

    mockWorkflow.use {
      mockBlubber.use {
        def runner = new PipelineRunner(new WorkflowScript())

        def variant = "foo"
        def labels = [foo: "a", bar: "b"]
        def context = URI.create("foo/dir")
        def excludes = [
          ".git",
          "*.md",
          "!README.md",
        ]

        assert runner.build(variant, labels, context, excludes) == "bf1e86190382"
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
                        "-n 'randomfoo' " +
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
                        "-n 'randomfoo' " +
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
                        "-n 'randomfoo' " +
                        "--debug --wait --timeout 120 " +
                        "--repo https://helm-charts.wikimedia.org/stable/ "

      assert cmd == expectedCmd

      throw new WorkflowException("foo");
    }

    mockWorkflow.demand.sh { cmd ->
      def expectedCmd = "helm --tiller-namespace='ci' " +
                        "delete --purge 'randomfoo'"

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
      def expectedCmd = "helm --tiller-namespace='ci' test --logs --cleanup 'foorelease'"

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
                        "helm --tiller-namespace='ci' test --logs --cleanup 'foorelease'"

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
      assert cmd == "docker tag 'fooID' 'internal.example/foorepo/fooname:footag'"
    }

    mockWorkflow.demand.sh { cmd ->
      assert cmd == "sudo /usr/local/bin/docker-pusher 'internal.example/foorepo/fooname:footag'"
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript(),
                                      registryInternal: 'internal.example',
                                      repository: 'foorepo')

      runner.registerAs("fooID", "fooname", "footag")
    }
  }

  void testRegisterAs_supportsRegistryPushMethod() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.sh { cmd ->
      assert cmd == "docker tag 'fooID' 'registry.example/foorepo/fooname:footag'"
    }

    mockWorkflow.demand.sh { args ->
      assert args.script == "mktemp -d"
      assert args.returnStdout == true

      "/tmp/foo-dir"
    }

    mockWorkflow.demand.writeJSON { args ->
      assert args.file == "/tmp/foo-dir/config.json"
      assert args.json == [ "credHelpers": [ "registry.example": "environment" ] ]
    }

    mockWorkflow.demand.withEnv { envs, closure ->
      assert envs == ["DOCKER_CREDENTIAL_HOST='registry.example'"]
      closure()
    }

    mockWorkflow.demand.withCredentials { creds, closure ->
      assert creds == [[$class: 'UsernamePasswordMultiBinding',
                        credentialsId: 'foo-credential-id',
                        passwordVariable: 'DOCKER_CREDENTIAL_USERNAME',
                        usernameVariable: 'DOCKER_CREDENTIAL_PASSWORD']]

      closure()
    }

    mockWorkflow.demand.sh { cmd ->
      assert cmd == "docker --config '/tmp/foo-dir' push 'registry.example/foorepo/fooname:footag'"
    }

    mockWorkflow.demand.dir { tempDir, closure ->
      assert tempDir == "/tmp/foo-dir"
      closure()
    }

    mockWorkflow.demand.deleteDir { }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript(),
                                      registry: 'registry.example',
                                      repository: 'foorepo',
                                      registryPushMethod: 'docker-push',
                                      registryCredential: 'foo-credential-id')

      runner.registerAs("fooID", "fooname", "footag")
    }
  }

  void testUpdateChart() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.sh { cmd ->
      assert cmd == "./update_version/update_version.py -s 'fooChart' -v 'fooVersion' "
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.updateChart("fooChart", "fooVersion", [])
    }    
  }

  void testUpdateChart_withEnvs() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.sh { cmd ->
      assert cmd == "./update_version/update_version.py -s 'fooChart' -v 'fooVersion' -e 'fooEnv' -e 'barEnv'"
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.updateChart("fooChart", "fooVersion", ["fooEnv", "barEnv"])
    }    
  }

  void testUpdateCharts() {
    def mockWorkflow = new MockFor(WorkflowScript)

    def mockEnv = [
      JOB_NAME: "fooJob",
      BUILD_NUMBER: "1234",
    ]

    mockWorkflow.demand.getEnv { mockEnv }
    mockWorkflow.demand.getEnv { mockEnv }

    mockWorkflow.demand.sh { cmd ->
      assert cmd == "git checkout -b 'randomfoo'"
    }

    mockWorkflow.demand.sh { cmd ->
      assert cmd == "./update_version/update_version.py -s 'fooChart' -v 'fooVersion' "
    }

    mockWorkflow.demand.sh { cmd ->
      assert cmd == """\
        |git add -A
        |git config user.email tcipriani+pipelinebot@wikimedia.org
        |git config user.name PipelineBot
        |git commit -m 'fooChart: pipeline bot promote' -m 'Promote fooChart to version fooVersion' -m 'Job: fooJob Build: 1234'
      """.stripMargin()
    }

    mockWorkflow.demand.withCredentials { list, Closure c ->
      c()
    }

    mockWorkflow.demand.sh{ cmd ->
      assert cmd == '''\
        |set +x
        |git config credential.username ${GIT_USERNAME}
        |git config credential.helper '!echo password=\${GIT_PASSWORD} #'
        |set -x
        |git push origin HEAD:refs/for/master%topic=pipeline-promote
      |'''.stripMargin()
    }

    mockWorkflow.demand.sh{ cmd -> 
      assert cmd == """\
        |set +e
        |git config --unset credential.helper 
        |git config --unset credential.username
        |set -e
      """.stripMargin()
    }

    mockWorkflow.demand.sh { cmd ->
      assert cmd == "git checkout master"
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.updateCharts([
        [chart: "fooChart", version: "fooVersion", environments: []]
      ])
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
      assert string == 'docker run --rm --name \'plib-run-randomfoo\' sha256:\'foo\''
    }

    mockWorkflow.demand.timeout { Map args, Closure c ->
      assert args.time == 20
      assert args.unit == "MINUTES"

      c()
    }

    mockWorkflow.demand.withCredentials { list, Closure c ->
      assert list == []
      c()
    }

    mockWorkflow.demand.sh { cmd ->
      assert cmd == 'set +x\ndocker run --rm --name \'plib-run-randomfoo\' sha256:\'foo\''
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.run("foo")
    }
  }

  void testRun_withEnvs() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.echo { string ->
      assert string == 'docker run --rm --name \'plib-run-randomfoo\' -e "foo=bar" sha256:\'foo\''
    }

    mockWorkflow.demand.timeout { Map args, Closure c ->
      assert args.time == 20
      assert args.unit == "MINUTES"

      c()
    }

    mockWorkflow.demand.withCredentials { list, Closure c ->
      assert list == []
      c()
    }

    mockWorkflow.demand.sh { cmd ->
      assert cmd == 'set +x\ndocker run --rm --name \'plib-run-randomfoo\' -e "foo=bar" sha256:\'foo\''
    }
    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.run("foo", [], [foo: "bar"])
    }
  }

  void testRun_withCreds() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.echo { string ->
      assert string == 'docker run --rm --name \'plib-run-randomfoo\' -e "SONAR_API_KEY=${SONAR_API_KEY}" sha256:\'foo\''
    }

    mockWorkflow.demand.timeout { Map args, Closure c ->
      assert args.time == 20
      assert args.unit == "MINUTES"

      c()
    }

    mockWorkflow.demand.withCredentials { list, Closure c ->
      assert list == [[$class:'StringBinding', credentialsId:'SONAR_API_KEY', variable:'SONAR_API_KEY']]
      c()
    }

    mockWorkflow.demand.sh { cmd ->
      assert cmd == 'set +x\ndocker run --rm --name \'plib-run-randomfoo\' -e "SONAR_API_KEY=${SONAR_API_KEY}" sha256:\'foo\''
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.run("foo", [], [:], [SONAR_API_KEY:'SONAR_API_KEY'])
    }
  }

  //TODO: update or remove when we figure out a better way to limit which creds are used
  void testRun_withCreds_OnlyAcceptsSonar() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      shouldFail(RuntimeException) {
        runner.run("foo", [], [:], [sonarid:'SONAR_API_KEY'])
      }
    }
  }

  void testRun_withCredsAndEnvs() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.echo { string ->
      assert string == 'docker run --rm --name \'plib-run-randomfoo\' -e "foo=bar" -e "SONAR_API_KEY=${SONAR_API_KEY}" sha256:\'foo\''
    }

    mockWorkflow.demand.timeout { Map args, Closure c ->
      assert args.time == 20
      assert args.unit == "MINUTES"

      c()
    }

    mockWorkflow.demand.withCredentials { list, Closure c ->
      assert list == [[$class:'StringBinding', credentialsId:'SONAR_API_KEY', variable:'SONAR_API_KEY']]
      c()
    }

    mockWorkflow.demand.sh { cmd ->
      assert cmd == 'set +x\ndocker run --rm --name \'plib-run-randomfoo\' -e "foo=bar" -e "SONAR_API_KEY=${SONAR_API_KEY}" sha256:\'foo\''
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.run("foo", [], [foo: "bar"], [SONAR_API_KEY:'SONAR_API_KEY'])
    }
  }

  void testRun_withOutput() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.with {
      echo { string ->
        assert string == 'docker run --rm --name \'plib-run-randomfoo\' sha256:\'foo\''
      }

      timeout { Map args, Closure c ->
        assert args.time == 20
        assert args.unit == "MINUTES"

        c()
      }

      withCredentials { list, Closure c ->
        assert list == []
        c()
      }

      sh { cmd ->
        assert cmd == 'set +x\ndocker run --rm --name \'plib-run-randomfoo\' sha256:\'foo\' | tee \'.pipeline/output.randomfoo\''
      }

      readFile { path ->
        assert path == ".pipeline/output.randomfoo"

        (
          "one\n" +
          "two\n" +
          "three\n" +
          "four\n"
        )
      }

      sh { cmd ->
        assert cmd == "rm -f '.pipeline/output.randomfoo'"
      }
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      assert runner.run("foo", [], [:], [:], 3) == (
        "two\n" +
        "three\n" +
        "four\n"
      )
    }
  }

}
