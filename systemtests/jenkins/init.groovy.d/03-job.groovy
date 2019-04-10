import hudson.plugins.git.*
import jenkins.model.Jenkins

import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def scm = new GitSCM("/var/lib/git/repo1")
scm.branches = [new BranchSpec("master")]

def job = new WorkflowJob(Jenkins.instance, "repo1")
job.definition = new CpsScmFlowDefinition(scm, "Jenkinsfile")

Jenkins.instance.reload()
