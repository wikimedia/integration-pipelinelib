import jenkins.model.Jenkins

Jenkins.instance.computers.each { c ->
  c.node.labelString += "pipelinelib pipelinelib-build pipelinelib-publish pipelinelib-promote"
}

Jenkins.instance.save()
