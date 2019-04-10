import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval

ScriptApproval.get().approveSignature('staticMethod java.net.URI create java.lang.String')
ScriptApproval.get().approveSignature('method java.net.URI resolve java.net.URI')
