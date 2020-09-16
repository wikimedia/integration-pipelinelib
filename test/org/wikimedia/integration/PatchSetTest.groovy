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
