package com.clipshare.comment;

import org.springframework.stereotype.Component;

// TODO: integrar Google Safe Browsing API (tier gratuito) para chequeo automático de
// phishing/malware — docs/SPEC.md sección 11.9. Hasta entonces, todo dominio que no esté en
// la lista estática blocked_link_domains se trata como "no marcado" (no como "verificado
// seguro" — a propósito no se pretende dar una garantía que no se puede sostener sin la
// integración real).
@Component
public class MockLinkSafetyService implements LinkSafetyService {

    @Override
    public SafetyResult checkDomain(String domain) {
        return SafetyResult.ok();
    }
}
