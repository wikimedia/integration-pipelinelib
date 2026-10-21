import groovy.mock.interceptor.MockFor
import static groovy.test.GroovyAssert.*
import groovy.util.GroovyTestCase

import java.io.FileNotFoundException
import java.net.URI

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

  void testAssignLocalName() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.sh { cmd ->
      assert cmd == "docker tag 'foo-image-id' 'localhost/plib-image-randomfoo'"
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      assert runner.assignLocalName('foo-image-id') == 'localhost/plib-image-randomfoo'
    }
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

    mockWorkflow.demand.with {
      fileExists { true }

      readFile { args ->
        assert args.file == ".pipeline/blubber.yaml"

        """|version: v4
           |base: ~
           |# some comment
           |variants:
           |  foo: {}
           |""".stripMargin()
      }

      writeFile { args ->
        assert args.text == """|# syntax=docker-registry.wikimedia.org/repos/releng/blubber/buildkit:v1
                               |version: v4
                               |base: ~
                               |# some comment
                               |variants:
                               |  foo: {}
                               |""".stripMargin()

        assert args.file ==~ /^\.pipeline\/blubber\.yaml\.[a-z0-9]+$/
      }

      pwd { "/workspace" }

      dir { context, Closure c ->
        assert context == "foo/context/dir"
        c()
      }

      fileExists { path -> true }

      sh {
        assert it == "cp '.dockerignore' '/workspace/.pipeline/dockerignore.bak.randomfoo'"
      }

      writeFile { args ->
        assert args.text == ".git\n" +
                            "*.md\n" +
                            "!README.md\n"
        assert args.file == ".dockerignore"
      }

      sh { args ->
        assert args.returnStdout
        assert args.script == "mktemp -d"

        return "/tmp/dir\n"
      }

      writeJSON { args ->
        assert args.file == "/tmp/dir/config.json"
        assert args.json == [ credsStore: "environment" ]
      }

      sh { script ->
        assert script == ("DOCKER_BUILDKIT=1 docker --config '/tmp/dir' " +
                          "build --pull --force-rm=true --label 'foo=a' --label 'bar=b' " +
                          "--iidfile '.pipeline/docker.iid.randomfoo' " +
                          "--file '.pipeline/blubber.yaml.randomfoo' " +
                          "--target 'foo' 'foo/context/dir'")
      }

      dir { path, c ->
        assert path == "/tmp/dir"
        c()
      }

      deleteDir {}

      readFile { path ->
        assert '.pipeline/docker.iid.randomfoo'

        return "sha256:bf1e86190382"
      }

      sh { script ->
        assert script == "rm -f '.pipeline/docker.iid.randomfoo'"
      }

      dir { path, c ->
        assert path == "foo/context/dir"
        c()
      }

      sh {
        assert it == "mv '/workspace/.pipeline/dockerignore.bak.randomfoo' '.dockerignore'"
      }
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(
        new WorkflowScript(),
        buildkitFrontend: "docker-registry.wikimedia.org/repos/releng/blubber/buildkit:v1",
      )

      def variant = "foo"
      def labels = [foo: "a", bar: "b"]
      def context = URI.create("foo/context/dir")
      def excludes = [
        ".git",
        "*.md",
        "!README.md",
      ]

      assert runner.build(variant, labels, context, excludes) == "bf1e86190382"
    }
  }

  void testBuild_enforcesFrontend() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.with {
      fileExists { true }

      readFile { args ->
        assert args.file == ".pipeline/blubber.yaml"

        // It seems that docker will use the last syntax= line in the header so
        // let's make sure that is the one that is checked against ours
        """|# syntax=docker-registry.wikimedia.org/repos/releng/blubber/buildkit:v1
           |# syntax=some.other.example/frontend
           |version: v4
           |base: ~
           |variants:
           |  foo: {}
           |""".stripMargin()
      }

      writeFile { args ->
        assert args.text == """|# syntax=docker-registry.wikimedia.org/repos/releng/blubber/buildkit:v1
                               |version: v4
                               |base: ~
                               |variants:
                               |  foo: {}
                               |""".stripMargin()

        assert args.file ==~ /^\.pipeline\/blubber\.yaml\.[a-z0-9]+$/
      }

      pwd { "/workspace" }

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

      sh { args ->
        assert args.returnStdout
        assert args.script == "mktemp -d"

        return "/tmp/dir\n"
      }

      writeJSON { args ->
        assert args.file == "/tmp/dir/config.json"
        assert args.json == [ credsStore: "environment" ]
      }

      sh { script ->
        assert script == ("DOCKER_BUILDKIT=1 docker --config '/tmp/dir' " +
                          "build --pull --force-rm=true --label 'foo=a' --label 'bar=b' " +
                           "--iidfile '.pipeline/docker.iid.randomfoo' " +
                           "--file '.pipeline/blubber.yaml.randomfoo' " +
                           "--target 'foo' 'foo/dir'")
      }

      dir { path, c ->
        assert path == "/tmp/dir"
        c()
      }

      deleteDir {}

      readFile { path ->
        assert '.pipeline/docker.iid.randomfoo'

        return "sha256:bf1e86190382"
      }

      sh { script ->
        assert script == "rm -f '.pipeline/docker.iid.randomfoo'"
      }
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(
        new WorkflowScript(),
        buildkitFrontend: "docker-registry.wikimedia.org/repos/releng/blubber/buildkit:v1",
      )

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

  void testCopyFilesFrom() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.sh { cmd ->
      assert cmd == """mkdir -p "\$(dirname 'dst/path')" && docker cp 'foo-container':'src/path' 'dst/path'"""
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.copyFilesFrom("foo-container", "src/path", "/foo/../../dst/foo/../path")
    }
  }

  void testCopyFilesFrom_sourceIsDirectory() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.sh { cmd ->
      assert cmd == """mkdir -p "\$(dirname 'dst/path')" && docker cp 'foo-container':'src/path/.' 'dst/path'"""
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.copyFilesFrom("foo-container", "src/path/", "/foo/../../dst/foo/../path")
    }
  }

  void testCopyFilesFrom_archive() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.with {
      sh { cmd ->
        assert cmd == """mkdir -p "\$(dirname 'dst/path')" && docker cp 'foo-container':'src/path' 'dst/path'"""
      }

      sh { args ->
        assert args.script == """[ -d 'dst/path' ]"""
        assert args.returnStatus

        return 1
      }

      getEnv { [ BUILD_URL: "http://an.example/job/123/" ] }

      archiveArtifacts { args ->
        assert args.artifacts == "dst/path"
        assert args.allowEmptyArchive == true
        assert args.followSymlinks == false
        assert args.onlyIfSuccessful == true
      }
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      def artifactURL = runner.copyFilesFrom("foo-container", "src/path", "dst/path", true)
      assert artifactURL == "http://an.example/job/123/artifact/dst/path"
    }
  }

  void testCopyFilesFrom_archive_directory() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.with {
      sh { cmd ->
        assert cmd == """mkdir -p "\$(dirname 'dst/path')" && docker cp 'foo-container':'src/path' 'dst/path'"""
      }

      sh { args ->
        assert args.script == """[ -d 'dst/path' ]"""
        assert args.returnStatus

        return 0
      }

      sh { cmd ->
        assert cmd == """tar zcf 'dst/path.tar.gz' -C 'dst/path' ."""
      }

      getEnv { [ BUILD_URL: "http://an.example/job/123/" ] }

      archiveArtifacts { args ->
        assert args.artifacts == "dst/path.tar.gz"
        assert args.allowEmptyArchive == true
        assert args.followSymlinks == false
        assert args.onlyIfSuccessful == true
      }
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      def artifactURL = runner.copyFilesFrom("foo-container", "src/path", "dst/path", true)
      assert artifactURL == "http://an.example/job/123/artifact/dst/path.tar.gz"
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
      def expectedCmd = "helm3 install 'randomfoo' " +
                        "'exampleChart' " +
                        "--namespace='ci' " +
                        "--values '.pipeline/values.yaml.randomfoo' " +
                        "--debug --wait --timeout 120s " +
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
                        "helm3 install 'randomfoo' " +
                        "'exampleChart' " +
                        "--namespace='ci' " +
                        "--values '.pipeline/values.yaml.randomfoo' " +
                        "--debug --wait --timeout 120s " +
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
      def expectedCmd = "helm3 install 'randomfoo' " +
                        "'exampleChart' " +
                        "--namespace='ci' " +
                        "--values '.pipeline/values.yaml.randomfoo' " +
                        "--debug --wait --timeout 120s " +
                        "--repo https://helm-charts.wikimedia.org/stable/ "

      assert cmd == expectedCmd

      throw new WorkflowException("foo");
    }

    mockWorkflow.demand.sh { cmd ->
      def expectedCmd = "helm3 uninstall 'randomfoo' --namespace='ci'"

      assert cmd == expectedCmd
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      shouldFail(WorkflowException) {
        runner.deploy("foo/name", "footag")
      }
    }
  }

  void testHasAllowedFrontend_allowsDefaultImageDifferentVersion() {
    def runner = new PipelineRunner(
      new WorkflowScript(),
      buildkitFrontend: "some.example/buildkit/frontend:v0",
      buildkitAllowedFrontends: ["some.example/buildkit/frontend"] as Set
    )

    assert runner.hasAllowedFrontend("# syntax=some.example/buildkit/frontend:v1")
  }

  void testHasAllowedFrontend_allowsAlternateImage() {
    def runner = new PipelineRunner(
      new WorkflowScript(),
      buildkitFrontend: "some.example/buildkit/frontend:v0",
      buildkitAllowedFrontends: [
        "some.example/buildkit/frontend",
        "other.example/buildkit/frontend"
      ] as Set
    )

    assert runner.hasAllowedFrontend("# syntax=other.example/buildkit/frontend:v1")
  }

  void testHasAllowedFrontend_disallowsUnknownImage() {
    def runner = new PipelineRunner(
      new WorkflowScript(),
      buildkitFrontend: "some.example/buildkit/frontend:v0",
      buildkitAllowedFrontends: [
        "some.example/buildkit/frontend",
        "other.example/buildkit/frontend"
      ] as Set
    )

    assert !runner.hasAllowedFrontend("# syntax=unknown.example/frontend:v0")
  }

  void testPurgeRelease_executesHelm() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.sh { cmd ->
      def expectedCmd = "helm3 uninstall 'foorelease' --namespace='ci'"

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
                        "helm3 uninstall 'foorelease' --namespace='ci'"

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
      def expectedCmd = "helm3 test 'foorelease'"

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
                        "helm3 test 'foorelease'"

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

    def runDemands = { pushfn ->
      mockWorkflow.demand.getEnv { mockEnv }
      mockWorkflow.demand.getEnv { mockEnv }

      mockWorkflow.demand.sh { cmd ->
        assert cmd == "git checkout -b 'randomfoo'"
      }

      mockWorkflow.demand.sh { cmd ->
        assert cmd == "./update_version/update_version.py -s 'fooChart' -v 'fooVersion' "
      }

      mockWorkflow.demand.withEnv { envs, Closure c ->
        assert envs == [
          'GIT_AUTHOR_NAME=PipelineBot',
          'GIT_COMMITTER_NAME=PipelineBot',
          'GIT_AUTHOR_EMAIL=tcipriani+pipelinebot@wikimedia.org',
          'GIT_COMMITTER_EMAIL=tcipriani+pipelinebot@wikimedia.org',
        ]
        c()
      }

      mockWorkflow.demand.sh { cmd ->
        assert cmd == """\
          |git add -A
          |git commit -m 'fooChart: pipeline bot promote' -m 'Promote fooChart to version fooVersion' -m 'Job: fooJob Build: 1234'
        |""".stripMargin()
      }

      mockWorkflow.demand.withCredentials { list, Closure c ->
        c()
      }

      mockWorkflow.demand.sh(pushfn)

      mockWorkflow.demand.sh{ cmd ->
        assert cmd == """\
          |set +e
          |git config --unset credential.helper
          |git config --unset credential.username
          |set -e
        |""".stripMargin()
      }

      mockWorkflow.demand.sh { cmd ->
        assert cmd == "git checkout master"
      }

    }

  //two reviewers
  def pushfn = { cmd ->
    assert cmd == '''\
      |set +x
      |git config credential.username ${GIT_USERNAME}
      |git config credential.helper '!echo password=\${GIT_PASSWORD} #'
      |set -x
      |git push origin 'HEAD:refs/for/master%topic=pipeline-promote,r=foo@baz.com,r=bar'
    |'''.stripMargin()
  }

    runDemands(pushfn)

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.updateCharts([
        [chart: "fooChart", version: "fooVersion", environments: []]
      ], ["foo@baz.com", "bar"])
    }

    //one reviewer
    pushfn = { cmd ->
      assert cmd == '''\
        |set +x
        |git config credential.username ${GIT_USERNAME}
        |git config credential.helper '!echo password=\${GIT_PASSWORD} #'
        |set -x
        |git push origin 'HEAD:refs/for/master%topic=pipeline-promote,r=foo@baz.com'
      |'''.stripMargin()
    }

    runDemands(pushfn)

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.updateCharts([
        [chart: "fooChart", version: "fooVersion", environments: []]
      ], ["foo@baz.com"])
    }

    //no reviewers
    pushfn = { cmd ->
      assert cmd == '''\
        |set +x
        |git config credential.username ${GIT_USERNAME}
        |git config credential.helper '!echo password=\${GIT_PASSWORD} #'
        |set -x
        |git push origin 'HEAD:refs/for/master%topic=pipeline-promote'
      |'''.stripMargin()
    }

    runDemands(pushfn)

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.updateCharts([
        [chart: "fooChart", version: "fooVersion", environments: []]
      ], [])
    }
  }

  void testScanImage() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.sh { cmd ->
      assert cmd == "docker tag 'foo-image-id' 'localhost/plib-image-randomfoo'"
    }

    mockWorkflow.demand.sh { cmd ->
      assert cmd == "docker run --rm --volume /var/run/docker.sock:/var/run/docker.sock " +
        "'syft:foo' 'docker:localhost/plib-image-randomfoo' " +
        "--select-catalogers +binary-classifier-cataloger --output spdx-json > '.pipeline/sbom.randomfoo'\n" +
        "test -s '.pipeline/sbom.randomfoo' || { echo \"syft gave no SBOM\" >&2; exit 1; }"
    }

    mockWorkflow.demand.readFile { path ->
      assert path == ".pipeline/sbom.randomfoo"

      '{"packages": []}'
    }

    mockWorkflow.demand.sh { Map args ->
      assert args.returnStdout
      assert args.script == "docker run --rm --interactive " +
        "--volume 'pipelinelib-grype-db:/grype-db' " +
        '-e "GRYPE_DB_CACHE_DIR=/grype-db" ' +
        "'grype:foo' --by-cve --output json < '.pipeline/sbom.randomfoo'"

      '''{"matches": [
        {"vulnerability": {"id": "CVE-0000-0001"}, "artifact": {"name": "foo", "version": "1.0"}}
      ]}'''
    }

    mockWorkflow.demand.sh { cmd ->
      assert cmd == "rm -f '.pipeline/sbom.randomfoo'"
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(
        new WorkflowScript(),
        grypeImage: "grype:foo",
        syftImage: "syft:foo",
        grypeBlockedVulnerabilities: ["CVE-0000-0002"],
      )

      runner.scanImage("foo-image-id")
    }
  }

  void testScanImage_withProxy() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.sh(2) { cmd -> }
    mockWorkflow.demand.readFile { path -> '{"packages": []}' }

    mockWorkflow.demand.sh { Map args ->
      assert args.script == "docker run --rm --interactive " +
        '-e "HTTP_PROXY=http://proxy.example:8080" -e "HTTPS_PROXY=http://proxy.example:8080" ' +
        "'grype:foo' --by-cve --output json < '.pipeline/sbom.randomfoo'"

      '{}'
    }

    mockWorkflow.demand.sh { cmd -> }

    mockWorkflow.use {
      def runner = new PipelineRunner(
        new WorkflowScript(),
        grypeImage: "grype:foo",
        grypeBlockedVulnerabilities: ["CVE-0000-0002"],
        grypeDBVolume: "",
        httpProxy: "http://proxy.example:8080",
      )

      runner.scanImage("foo-image-id")
    }
  }

  void testScanImage_emptyReport() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.sh(2) { cmd -> }
    mockWorkflow.demand.readFile { path -> '{"packages": []}' }
    mockWorkflow.demand.sh { Map args -> "\n" }
    mockWorkflow.demand.sh { cmd -> }

    mockWorkflow.use {
      def runner = new PipelineRunner(
        new WorkflowScript(),
        grypeBlockedVulnerabilities: ["CVE-0000-0002"],
      )

      def msg = shouldFail(RuntimeException) {
        runner.scanImage("foo-image-id")
      }

      assert msg.message == "grype gave no report for image foo-image-id"
    }
  }

  void testScanImage_blocked() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.sh(2) { cmd -> }
    mockWorkflow.demand.readFile { path -> '{"packages": []}' }
    mockWorkflow.demand.sh { Map args ->
      '''{"matches": [
        {"vulnerability": {"id": "CVE-0000-0001"}, "artifact": {"name": "foo", "version": "1.0"}},
        {
          "vulnerability": {"id": "GHSA-xxxx-yyyy-zzzz"},
          "relatedVulnerabilities": [{"id": "CVE-0000-0002"}],
          "artifact": {"name": "bar", "version": "2.0"}
        }
      ]}'''
    }
    mockWorkflow.demand.sh { cmd -> }

    mockWorkflow.use {
      def runner = new PipelineRunner(
        new WorkflowScript(),
        grypeBlockedVulnerabilities: ["CVE-0000-0002"],
      )

      def msg = shouldFail(RuntimeException) {
        runner.scanImage("foo-image-id")
      }

      assert msg.message == "image foo-image-id contains blocked vulnerabilities:\n  CVE-0000-0002: bar@2.0"
    }
  }

  void testScanImage_noBlockedVulnerabilities() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.use {
      def runner = new PipelineRunner(
        new WorkflowScript(),
        grypeBlockedVulnerabilities: [],
        vulnerableVersionRules: [],
      )

      runner.scanImage("foo-image-id")
    }
  }

  void testScanImage_defaultBlockedVulnerabilities() {
    def runner = new PipelineRunner(new WorkflowScript())

    assert "CVE-2026-109618" in runner.grypeBlockedVulnerabilities
    assert "CVE-2026-109619" in runner.grypeBlockedVulnerabilities
  }

  void testScanImage_vulnerableVersion() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.sh(2) { cmd -> }

    mockWorkflow.demand.readFile { path ->
      '''{"packages": [
        {
          "name": "php-cli",
          "versionInfo": "8.5.9",
          "externalRefs": [
            {
              "referenceCategory": "SECURITY",
              "referenceType": "cpe23Type",
              "referenceLocator": "cpe:2.3:a:php:php:8.5.9:*:*:*:*:*:*:*"
            },
            {
              "referenceCategory": "PACKAGE-MANAGER",
              "referenceType": "purl",
              "referenceLocator": "pkg:generic/php-cli@8.5.9"
            }
          ]
        }
      ]}'''
    }

    // Grype does not run, because no vulnerability IDs are blocked.
    mockWorkflow.demand.sh { cmd ->
      assert cmd == "rm -f '.pipeline/sbom.randomfoo'"
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(
        new WorkflowScript(),
        grypeBlockedVulnerabilities: [],
        vulnerableVersionRules: [
          [ids: ["CVE-0000-0001"], cpe: "php:php", fixedVersions: ["8.4.27", "8.5.12"]],
        ],
      )

      def msg = shouldFail(RuntimeException) {
        runner.scanImage("foo-image-id")
      }

      assert msg.message == "image foo-image-id contains blocked vulnerabilities:\n  CVE-0000-0001: php-cli@8.5.9"
    }
  }

  void testScanImage_defaultVulnerableVersionRules() {
    def runner = new PipelineRunner(new WorkflowScript())
    def rule = runner.vulnerableVersionRules.find { "CVE-2026-109618" in it.ids }

    assert rule.ids == ["CVE-2026-109618", "CVE-2026-109619"]
    assert rule.cpe == "php:php"
    assert rule.fixedVersions == ["8.2.35", "8.3.36", "8.4.27", "8.5.12"]

    // Packages from the PHP source package match. Packages from other
    // source packages do not match.
    assert "pkg:deb/debian/php8.5-cli@8.5.9-1%2Bwmf12u1?arch=amd64&distro=debian-12&upstream=php8.5" =~ rule.purl
    assert "pkg:deb/debian/php8.2-common@8.2.20-1?upstream=php8.2%408.2.20-1&distro=debian-12" =~ rule.purl
    assert !("pkg:deb/debian/php8.5-apcu@5.1.28-2%2Bwmf12u1?arch=amd64&distro=debian-12&upstream=php-apcu" =~ rule.purl)
    assert !("pkg:deb/debian/php-common@2%3A100%2Bwmf12u1?arch=amd64&distro=debian-12&upstream=php-defaults%40100%2Bwmf12u1" =~ rule.purl)
    assert !("pkg:generic/php8.5@8.5.9?upstream=php8.5" =~ rule.purl)
  }

  void testFindVulnerableVersions() {
    def sbom = Utility.parseJson('''{"packages": [
      {
        "name": "php", "versionInfo": "8.4.20",
        "externalRefs": [
          {"referenceType": "cpe23Type", "referenceLocator": "cpe:2.3:a:php:php:8.4.20:*:*:*:*:*:*:*"},
          {"referenceType": "purl", "referenceLocator": "pkg:generic/php@8.4.20"}
        ]
      },
      {
        "name": "php-fpm", "versionInfo": "8.5.12",
        "externalRefs": [
          {"referenceType": "cpe23Type", "referenceLocator": "cpe:2.3:a:php:php:8.5.12:*:*:*:*:*:*:*"}
        ]
      },
      {
        "name": "php8.5-cli", "versionInfo": "8.5.9-1+wmf12u1",
        "externalRefs": [
          {"referenceType": "cpe23Type", "referenceLocator": "cpe:2.3:a:php8.5-cli:php8.5-cli:8.5.9-1\\\\+wmf12u1:*:*:*:*:*:*:*"},
          {"referenceType": "purl", "referenceLocator": "pkg:deb/debian/php8.5-cli@8.5.9-1%2Bwmf12u1?arch=amd64&upstream=php8.5"}
        ]
      },
      {
        "name": "php8.5-apcu", "versionInfo": "5.1.28-2+wmf12u1",
        "externalRefs": [
          {"referenceType": "purl", "referenceLocator": "pkg:deb/debian/php8.5-apcu@5.1.28-2%2Bwmf12u1?arch=amd64&upstream=php-apcu"}
        ]
      },
      {
        "name": "nginx", "versionInfo": "1.0.0",
        "externalRefs": [
          {"referenceType": "cpe23Type", "referenceLocator": "cpe:2.3:a:nginx:nginx:1.0.0:*:*:*:*:*:*:*"}
        ]
      },
      {
        "name": "no-refs", "versionInfo": "1.0.0"
      }
    ]}''')

    // Only the CPE.
    def rules = [
      [ids: ["CVE-0000-0001", "CVE-0000-0002"], cpe: "php:php", fixedVersions: ["8.4.27", "8.5.12"]],
    ]

    assert PipelineRunner.findVulnerableVersions(sbom, rules) as Set == [
      [id: "CVE-0000-0001", artifact: "php@8.4.20"],
      [id: "CVE-0000-0002", artifact: "php@8.4.20"],
    ] as Set

    // Only the purl.
    rules = [
      [ids: ["CVE-0000-0001"], purl: '[?&]upstream=php\\d', fixedVersions: ["8.4.27", "8.5.12"]],
    ]

    assert PipelineRunner.findVulnerableVersions(sbom, rules) == [
      [id: "CVE-0000-0001", artifact: "php8.5-cli@8.5.9-1+wmf12u1"],
    ]

    // The CPE or the purl.
    rules[0].cpe = "php:php"

    assert PipelineRunner.findVulnerableVersions(sbom, rules) as Set == [
      [id: "CVE-0000-0001", artifact: "php8.5-cli@8.5.9-1+wmf12u1"],
      [id: "CVE-0000-0001", artifact: "php@8.4.20"],
    ] as Set

    assert PipelineRunner.findVulnerableVersions(sbom, []) == []
  }

  void testFormatVulnerabilities() {
    def vulnerabilities = [
      [id: "CVE-0000-0002", artifact: "foo@1.0"],
      [id: "CVE-0000-0001", artifact: "foo@1.0"],
      [id: "CVE-0000-0001", artifact: "bar@2.0"],
      [id: "CVE-0000-0001", artifact: "foo@1.0"],
    ]

    assert PipelineRunner.formatVulnerabilities(vulnerabilities) ==
      "  CVE-0000-0001: bar@2.0, foo@1.0\n" +
      "  CVE-0000-0002: foo@1.0"
  }

  void testIsVulnerableVersion() {
    def fixed = ["8.2.35", "8.3.36", "8.4.27", "8.5.12"]

    // Less than the fixed version of its branch.
    assert PipelineRunner.isVulnerableVersion("8.2.34", fixed)
    assert PipelineRunner.isVulnerableVersion("8.5.9", fixed)
    assert PipelineRunner.isVulnerableVersion("1:8.4.26-1~deb13u1", fixed)

    // Equal to or more than the fixed version of its branch.
    assert !PipelineRunner.isVulnerableVersion("8.2.35", fixed)
    assert !PipelineRunner.isVulnerableVersion("8.3.40", fixed)
    assert !PipelineRunner.isVulnerableVersion("8.5.12-1+wmf13u1", fixed)
    assert !PipelineRunner.isVulnerableVersion("v8.4.27", fixed)

    // Branches that are not maintained.
    assert PipelineRunner.isVulnerableVersion("8.1.99", fixed)
    assert PipelineRunner.isVulnerableVersion("7.4.33", fixed)

    // Branches that are more recent than all of the listed branches.
    assert !PipelineRunner.isVulnerableVersion("8.6.0", fixed)
    assert !PipelineRunner.isVulnerableVersion("9.0.0", fixed)

    // Unknown versions.
    assert PipelineRunner.isVulnerableVersion("", fixed)
    assert PipelineRunner.isVulnerableVersion(null, fixed)
    assert PipelineRunner.isVulnerableVersion("UNKNOWN", fixed)
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
      assert string == 'docker run --name \'plib-run-randomfoo\' --rm=false sha256:\'foo\''
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
      assert cmd == '#!/bin/bash\nset +o xtrace -o pipefail\ndocker run --name \'plib-run-randomfoo\' --rm=false sha256:\'foo\''
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.run("foo")
    }
  }

  void testRun_with_remove() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.echo { string ->
      assert string == 'docker run --name \'plib-run-randomfoo\' --rm=true sha256:\'foo\''
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
      assert cmd == '#!/bin/bash\nset +o xtrace -o pipefail\ndocker run --name \'plib-run-randomfoo\' --rm=true sha256:\'foo\''
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.run([imageID: "foo",
                    removeContainer: true])
    }
  }

  void testRun_withEnvs() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.echo { string ->
      assert string == 'docker run --name \'plib-run-randomfoo\' --rm=false -e "foo=bar" sha256:\'foo\''
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
      assert cmd == '#!/bin/bash\nset +o xtrace -o pipefail\ndocker run --name \'plib-run-randomfoo\' --rm=false -e "foo=bar" sha256:\'foo\''
    }
    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.run([imageID: "foo",
                    envVars: [foo: "bar"]])
                   
    }
  }

  void testRun_withCreds() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.echo { string ->
      assert string == 'docker run --name \'plib-run-randomfoo\' --rm=false -e "SONAR_API_KEY=${SONAR_API_KEY}" -e "test=${test}" sha256:\'foo\''
    }

    mockWorkflow.demand.timeout { Map args, Closure c ->
      assert args.time == 20
      assert args.unit == "MINUTES"

      c()
    }

    mockWorkflow.demand.withCredentials { list, Closure c ->
      assert list == [
        [$class:'StringBinding', credentialsId:'SONAR_API_KEY', variable:'SONAR_API_KEY'],
        [$class:'StringBinding', credentialsId:'TEST', variable:'test']
      ]
      c()
    }

    mockWorkflow.demand.sh { cmd ->
      assert cmd == '#!/bin/bash\nset +o xtrace -o pipefail\ndocker run --name \'plib-run-randomfoo\' --rm=false -e "SONAR_API_KEY=${SONAR_API_KEY}" -e "test=${test}" sha256:\'foo\''
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript(), allowedCredentials: [SONAR_API_KEY: "StringBinding", TEST: "StringBinding"])

      runner.run([
          imageID: "foo",
          creds: [[id: 'SONAR_API_KEY', name: 'SONAR_API_KEY'], [id: 'TEST', name: 'test']]
        ])
    }
  }

  void testRun_withCredsAndEnvs() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.echo { string ->
      assert string == 'docker run --name \'plib-run-randomfoo\' --rm=false -e "foo=bar" -e "SONAR_API_KEY=${SONAR_API_KEY}" sha256:\'foo\''
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
      assert cmd == '#!/bin/bash\nset +o xtrace -o pipefail\ndocker run --name \'plib-run-randomfoo\' --rm=false -e "foo=bar" -e "SONAR_API_KEY=${SONAR_API_KEY}" sha256:\'foo\''
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript(), allowedCredentials: [SONAR_API_KEY: 'StringBinding'])

      runner.run([
          imageID: "foo",
          envVars: [foo: "bar"],
          creds: [[id: 'SONAR_API_KEY', name: 'SONAR_API_KEY']],
        ])
          
    }
  }

  void testRun_withOutput() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.with {
      echo { string ->
        assert string == 'docker run --name \'plib-run-randomfoo\' --rm=false sha256:\'foo\''
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
        assert cmd == '#!/bin/bash\nset +o xtrace -o pipefail\ndocker run --name \'plib-run-randomfoo\' --rm=false sha256:\'foo\' | tee \'.pipeline/output.randomfoo\''
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

      def result = runner.run([
          imageID: "foo",
          outputLines: 3])

      assert result.output == (
        "two\n" +
        "three\n" +
        "four\n"
      )
    }
  }

  void testWithOutput() {
    def mockWorkflow = new MockFor(WorkflowScript)
    def tmpFile = '.pipeline/output.randomfoo'

    mockWorkflow.demand.with {
      readFile { path ->
        assert path == tmpFile

        return "foo content"
      }

      sh { cmd ->
        assert cmd == "rm -f '${tmpFile}'"
      }
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      def output = runner.withOutput("foo") { cmd ->
        assert cmd == "foo | tee '${tmpFile}'"
      }

      assert output == "foo content"
    }
  }

  void testWithOutput_noLines() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      def output = runner.withOutput("foo", 0) { cmd ->
        assert cmd == "foo"
      }

      assert output == ""
    }
  }

  void testWithOutput_lines() {
    def mockWorkflow = new MockFor(WorkflowScript)
    def tmpFile = '.pipeline/output.randomfoo'
    def lines = 2

    mockWorkflow.demand.with {
      readFile { path ->
        assert path == tmpFile

        return (
          "some\n" +
          "foo\n" +
          "content\n"
        )
      }

      sh { cmd ->
        assert cmd == "rm -f '${tmpFile}'"
      }
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      def output = runner.withOutput("foo", lines) { cmd ->
        assert cmd == "foo | tee '${tmpFile}'"
      }

      assert output == (
        "foo\n" +
        "content\n"
      )
    }
  }

  void testWithTempFile() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.with {
      sh { cmd ->
        assert cmd == "rm -f '.pipeline/prefix.randomfoo'"
      }
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.withTempFile("prefix.") { tempFile ->
        assert tempFile == ".pipeline/prefix.randomfoo"
      }
    }
  }

  void testWithTempFile_noDeletion() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript(),
        deleteTempFiles: false,
      )

      runner.withTempFile("prefix.") { tempFile ->
        assert tempFile == ".pipeline/prefix.randomfoo"
      }
    }
  }

  void testWithTempDirectory() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.with {
      sh { args ->
        assert args.returnStdout
        assert args.script == "mktemp -d"

        return "/tmp/dir\n"
      }

      dir { path, c ->
        assert path == "/tmp/dir"
        c()
      }

      deleteDir {}
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript())

      runner.withTempDirectory() { tempDir ->
        assert tempDir == "/tmp/dir"
      }
    }
  }

  void testWithTempDirectory_noDeletion() {
    def mockWorkflow = new MockFor(WorkflowScript)

    mockWorkflow.demand.with {
      sh { args ->
        assert args.returnStdout
        assert args.script == "mktemp -d"

        return "/tmp/dir\n"
      }
    }

    mockWorkflow.use {
      def runner = new PipelineRunner(new WorkflowScript(),
        deleteTempFiles: false
      )

      runner.withTempDirectory() { tempDir ->
        assert tempDir == "/tmp/dir"
      }
    }
  }
}
