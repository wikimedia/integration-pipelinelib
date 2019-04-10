import jenkins.model.Jenkins

Jenkins.instance.computers.each { c ->
  c.node.labelString += "blubber"
}

Jenkins.instance.save()
