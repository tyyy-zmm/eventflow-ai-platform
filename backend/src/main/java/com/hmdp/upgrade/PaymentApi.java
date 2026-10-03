package com.hmdp.upgrade;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v2/account/payments")
public class PaymentApi {
    public record Create(String requestId) {}
    public record Receipt(String channelId,int amountCents) {}
    private final Payments payments;
    @Value("${upgrade.sandbox-payments:false}") private boolean sandbox;
    public PaymentApi(Payments payments) {this.payments=payments;}
    private long user(HttpServletRequest request) {return ((Auth.Identity)request.getAttribute("identity")).user();}
    private void enabled() {if(!sandbox) throw new Problem(404,"SANDBOX_PAYMENTS_DISABLED");}
    @PostMapping public Object create(HttpServletRequest request,@RequestBody Create body) {enabled();return payments.create(user(request),body.requestId());}
    @GetMapping("/{id}") public Object get(HttpServletRequest request,@PathVariable String id) {enabled();return payments.get(user(request),id);}
    @PostMapping("/{id}/sandbox-receipt") public Object receipt(HttpServletRequest request,@PathVariable String id,@RequestBody Receipt body) {
        enabled();return payments.received(user(request),id,body.channelId(),body.amountCents());
    }
}
