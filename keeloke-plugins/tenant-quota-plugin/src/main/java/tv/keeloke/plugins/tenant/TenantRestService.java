package tv.keeloke.plugins.tenant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;

/**
 * GET  /keeloke/v1/tenants/usage           -> usage analytics for every tenant (app)
 * POST /keeloke/v1/tenants/{app}/quota?maxConcurrentStreams=N -> override a tenant's quota
 */
@RestController
@RequestMapping("/keeloke/v1/tenants")
public class TenantRestService {

    @Autowired
    private TenantQuotaPlugin tenantQuotaPlugin;

    @GetMapping("/usage")
    public Collection<TenantUsage> usage() {
        return tenantQuotaPlugin.allTenantUsage().values();
    }

    @PostMapping("/{app}/quota")
    public void setQuota(@PathVariable String app, @RequestParam int maxConcurrentStreams) {
        tenantQuotaPlugin.setQuotaForTenant(app, maxConcurrentStreams);
    }
}
