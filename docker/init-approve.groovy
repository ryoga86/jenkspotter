// Local test instance only: approve the Active Choices groovy scripts created by Job DSL.
import hudson.security.ACL
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval
Thread.start {
    20.times {
        sleep 10000
        def ctx = ACL.as2(ACL.SYSTEM2)
        try {
            def approval = ScriptApproval.get()
            approval.pendingScripts.toList().each { approval.approveScript(it.hash) }
        } finally {
            ctx.close()
        }
    }
}
