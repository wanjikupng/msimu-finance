package com.tracom.lipamdogo;

import com.jayway.jsonpath.JsonPath;
import com.tracom.lipamdogo.model.LoanContract;
import com.tracom.lipamdogo.model.PaymentPlan;
import com.tracom.lipamdogo.repository.LoanContractRepository;
import com.tracom.lipamdogo.repository.PaymentPlanRepository;
import com.tracom.lipamdogo.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The contract is built from the terms the customer was quoted at submission,
 * not from the live plan: the installment follows the plan term, and editing
 * a plan afterwards cannot change an application already in the queue.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FinancingTermsSnapshotIntegrationTest {

    private static final AtomicLong IMEI_SEQUENCE = new AtomicLong(920000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PaymentPlanRepository paymentPlanRepository;

    @Autowired
    private LoanContractRepository loanContractRepository;

    @Test
    void contractUsesTheQuotedInstallmentEvenIfThePlanChangesBeforeApproval() throws Exception {
        String admin = loginAs("admin@tracom.local", "Admin@1234");
        String customer = registerAndLogin();
        Long deviceId = createDevice(admin);

        PaymentPlan plan = new PaymentPlan();
        plan.setPlanName("Snapshot 90 Days " + UUID.randomUUID());
        plan.setDurationDays(90);
        plan.setDepositAmount(java.math.BigDecimal.ZERO);
        plan.setDailyAmount(java.math.BigDecimal.ONE);
        PaymentPlan saved = paymentPlanRepository.save(plan);

        Long contractId = approveContract(customer, admin, deviceId, saved.getId(), () -> {
            // An admin edits the plan while the application waits in the queue.
            PaymentPlan edited = paymentPlanRepository.findById(saved.getId()).orElseThrow();
            edited.setDurationDays(30);
            edited.setDailyAmount(new java.math.BigDecimal("999"));
            paymentPlanRepository.save(edited);
        });

        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        // Galaxy A15: 18,000 - 4,000 deposit = 14,000 over 90 days = 155.56 -> 156.
        assertThat(contract.getDailyAmount()).isEqualByComparingTo("156");
        assertThat(contract.getTotalPayable()).isEqualByComparingTo("14000");
    }

    private String registerAndLogin() throws Exception {
        String email = "terms-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\": \"Terms Customer\", \"email\": \"%s\", \"password\": \"Secret123\"}"
                                .formatted(email)))
                .andExpect(status().isCreated());
        return loginAs(email, "Secret123");
    }

    private String loginAs(String email, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }

    private Long createDevice(String adminToken) throws Exception {
        Long productId = productRepository.findByName("Samsung Galaxy A15").orElseThrow().getId();
        MvcResult created = mockMvc.perform(post("/api/v1/admin/devices")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": %d, \"imei\": \"%s\", \"serialNumber\": \"%s\"}"
                                .formatted(productId,
                                        String.format("354%012d", IMEI_SEQUENCE.incrementAndGet()),
                                        "TS-" + UUID.randomUUID().toString().replace("-", "")
                                                .substring(0, 10).toUpperCase())))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private Long approveContract(String customerToken, String officerToken, Long deviceId, Long planId,
                                 Runnable beforeApproval) throws Exception {
        MvcResult submitted = mockMvc.perform(post("/api/v1/financing/applications")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\": %d, \"depositAmount\": 4000, \"paymentPlanId\": %d}"
                                .formatted(deviceId, planId)))
                .andExpect(status().isOk())
                .andReturn();
        Long applicationId = ((Number) JsonPath.read(
                submitted.getResponse().getContentAsString(), "$.id")).longValue();
        mockMvc.perform(post("/api/v1/admin/financing/applications/{id}/review", applicationId)
                        .header("Authorization", "Bearer " + officerToken))
                .andExpect(status().isOk());
        beforeApproval.run();
        MvcResult approved = mockMvc.perform(post("/api/v1/admin/financing/applications/{id}/approve", applicationId)
                        .header("Authorization", "Bearer " + officerToken))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(approved.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
