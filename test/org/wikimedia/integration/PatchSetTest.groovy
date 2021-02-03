import groovy.util.GroovyTestCase
import java.net.URI

import org.wikimedia.integration.PatchSet

class PatchSetTest extends GroovyTestCase {
  void testFromZuul() {
    def patchset = PatchSet.fromZuul(
      ZUUL_URL: "ssh://foo.server:123",
      ZUUL_PROJECT: "foo/project",
      ZUUL_REF: "refs/zuul/master/Zfoo",
      ZUUL_COMMIT: "foosha",
    )

    assert patchset.commit == "foosha"
    assert patchset.project == "foo/project"
    assert patchset.ref == "refs/zuul/master/Zfoo"
    assert patchset.remote == new URI("ssh://foo.server:123/foo/project")
  }

  void testGetSCM() {
    def patchset = new PatchSet(
      commit: "foosha",
      project: "foo/project",
      ref: "refs/zuul/master/Zfoo",
      remote: new URI("ssh://foo.server:123/foo/project"),
    )

    def scm = patchset.getSCM()

    assert scm.userRemoteConfigs[0].url == "ssh://foo.server:123/foo/project"
    assert scm.userRemoteConfigs[0].refspec == "refs/zuul/master/Zfoo"
    assert scm.branches[0].name == "foosha"
  }

  void testGetSCM_noSubmodules() {
    def patchset = new PatchSet(
      commit: "foosha",
      project: "foo/project",
      ref: "refs/zuul/master/Zfoo",
      remote: new URI("ssh://foo.server:123/foo/project"),
    )

    def scm = patchset.getSCM(submodules: false)

    assert scm.extensions.contains(
      [
        $class: 'SubmoduleOption',
        disableSubmodules: true,
      ]
    )
  }

  void testGetSCM_shallowDefaultDepth() {
    def patchset = new PatchSet(
      commit: "foosha",
      project: "foo/project",
      ref: "refs/zuul/master/Zfoo",
      remote: new URI("ssh://foo.server:123/foo/project"),
    )

    def scm = patchset.getSCM(shallow: true)

    assert scm.extensions.contains(
      [
        $class: 'CloneOption',
        depth: 20,
        shallow: true,
        noTags: true,
      ]
    )

    assert scm.extensions.contains(
      [
        $class: 'SubmoduleOption',
        depth: 20,
        shallow: true,
        recursiveSubmodules: true,
      ]
    )
  }

  void testGetSCM_shallowDepth() {
    def patchset = new PatchSet(
      commit: "foosha",
      project: "foo/project",
      ref: "refs/zuul/master/Zfoo",
      remote: new URI("ssh://foo.server:123/foo/project"),
    )

    def scm = patchset.getSCM(shallow: true, depth: 1)

    assert scm.extensions.contains(
      [
        $class: 'CloneOption',
        depth: 1,
        shallow: true,
        noTags: true,
      ]
    )

    assert scm.extensions.contains(
      [
        $class: 'SubmoduleOption',
        depth: 1,
        shallow: true,
        recursiveSubmodules: true,
      ]
    )
  }

  void testGetSCM_target() {
    def patchset = new PatchSet(
      commit: "foosha",
      project: "foo/project",
      ref: "refs/zuul/master/Zfoo",
      remote: new URI("ssh://foo.server:123/foo/project"),
    )

    def scm = patchset.getSCM(target: "foo/target/dir")

    assert scm.extensions.contains(
      [
        $class: 'RelativeTargetDirectory',
        relativeTargetDir: "foo/target/dir/",
      ]
    )
  }

  void testGetSCM_useCredentials() {
    def patchset = new PatchSet(
      commit: "foosha",
      project: "foo/project",
      ref: "refs/zuul/master/Zfoo",
      remote: new URI("ssh://foo.server:123/foo/project"),
    )

    def scm = patchset.getSCM([credentialsID: "gerrit.pipelinebot"])

    assert scm.userRemoteConfigs[0].url == "ssh://foo.server:123/foo/project"
    assert scm.userRemoteConfigs[0].refspec == "refs/zuul/master/Zfoo"
    assert scm.userRemoteConfigs[0].credentialsId == "gerrit.pipelinebot"
    assert scm.branches[0].name == "foosha"
  }
}
