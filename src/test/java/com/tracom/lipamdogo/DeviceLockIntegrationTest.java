package com.tracom.lipamdogo;

import com.jayway.jsonpath.JsonPath;
import com.tracom.lipamdogo.model.LoanContract;
import com.tracom.lipamdogo.repository.LoanContractRepository;
import com.tracom.lipamdogo.repository.PaymentPlanRepository;
import com.tracom.lipamdogo.repository.ProductRepository;
import com.tracom.lipamdogo.security.UnlockCodeCodec;
import com.tracom.lipamdogo.support.SystemTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pay-as-you-go device lock: provisioning at approval, a new signed code each
 * time the paid-through date moves, lock after the grace days, permanent
 * unlock on payoff, and who may see what.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeviceLockIntegrationTest {

    private static final AtomicLong IMEI_SEQUENCE = new AtomicLong(930000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PaymentPlanRepository paymentPlanRepository;

    @Autowired
    private LoanContractRepository loanContractRepository;

    @Test
    void codesFollowThePaidThroughDateAndVerifyWithTheDeviceKey() throws Exception {
        String admin = loginAs("admin@tracom.local", "Admin@1234");
        String customer = registerAndLogin();
        Long deviceId = createDevice(admin);
        Long contractId = approveContract(customer, admin, deviceId);

        // Provisioned at approval: sequence 1, unlocked until due date + 2 grace days.
        String lock = getLock(customer, contractId);
        LocalDate due = LocalDate.parse(JsonPath.read(lock, "$.nextPaymentDueDate"));
        assertThat((String) JsonPath.read(lock, "$.lockState")).isEqualTo("UNLOCKED");
        assertThat((Integer) JsonPath.read(lock, "$.latestCode.sequence")).isEqualTo(1);
        assertThat((String) JsonPath.read(lock, "$.latestCode.reason")).isEqualTo("PROVISIONED");
        assertThat((String) JsonPath.read(lock, "$.latestCode.validUntil")).isEqualTo(due.plusDays(2).toString());

        // The code verifies with the key a technician loads into the device.
        MvcResult provisioning = mockMvc.perform(post("/api/v1/admin/devices/{id}/provisioning", deviceId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        String secret = JsonPath.read(provisioning.getResponse().getContentAsString(), "$.secretKey");
        String deviceCode = JsonPath.read(provisioning.getResponse().getContentAsString(), "$.deviceCode");
        String code = JsonPath.read(lock, "$.latestCode.code");
        var decoded = UnlockCodeCodec.verify(secret, deviceCode, code).orElseThrow();
        assertThat(decoded.sequence()).isEqualTo(1);

        // A partial payment does not complete an installment: nothing changes on the device.
        pay(customer, contractId, "30");
        assertThat((Integer) JsonPath.read(getLock(customer, contractId), "$.latestCode.sequence")).isEqualTo(1);

        // Completing the installment moves the paid-through date: new code, one day later.
        String payment = pay(customer, contractId, "40");
        lock = getLock(customer, contractId);
        assertThat((Integer) JsonPath.read(lock, "$.latestCode.sequence")).isEqualTo(2);
        assertThat((String) JsonPath.read(lock, "$.latestCode.reason")).isEqualTo("PAYMENT");
        assertThat((String) JsonPath.read(lock, "$.latestCode.paymentReference")).isEqualTo(payment);
        assertThat((String) JsonPath.read(lock, "$.latestCode.validUntil")).isEqualTo(due.plusDays(3).toString());
        assertThat(UnlockCodeCodec.verify(secret, deviceCode, JsonPath.read(lock, "$.latestCode.code"))).isPresent();

        // Reversing it moves the date back with a newer code (the device must not keep the extra day).
        mockMvc.perform(post("/api/v1/payments/{ref}/reverse", payment)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Duplicate counter entry\"}"))
                .andExpect(status().isOk());
        lock = getLock(customer, contractId);
        assertThat((Integer) JsonPath.read(lock, "$.latestCode.sequence")).isEqualTo(3);
        assertThat((String) JsonPath.read(lock, "$.latestCode.reason")).isEqualTo("REVERSAL");
        assertThat((String) JsonPath.read(lock, "$.latestCode.validUntil")).isEqualTo(due.plusDays(2).toString());

        // Paying everything off issues a permanent unlock.
        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        pay(customer, contractId, contract.getAmountRemaining().toPlainString());
        lock = getLock(customer, contractId);
        assertThat((String) JsonPath.read(lock, "$.lockState")).isEqualTo("OWNED");
        assertThat((String) JsonPath.read(lock, "$.latestCode.reason")).isEqualTo("PAID_OFF");
        assertThat((Boolean) JsonPath.read(lock, "$.latestCode.permanent")).isTrue();
        assertThat(UnlockCodeCodec.verify(secret, deviceCode, JsonPath.read(lock, "$.latestCode.code"))
                .orElseThrow().permanent()).isTrue();
    }

    @Test
    void deviceLocksOnlyAfterTheGraceDays() throws Exception {
        String admin = loginAs("admin@tracom.local", "Admin@1234");
        String customer = registerAndLogin();
        Long contractId = approveContract(customer, admin, createDevice(admin));

        setDueDate(contractId, SystemTime.today().minusDays(1));
        assertThat((String) JsonPath.read(getLock(customer, contractId), "$.lockState")).isEqualTo("GRACE");

        setDueDate(contractId, SystemTime.today().minusDays(2));
        assertThat((String) JsonPath.read(getLock(customer, contractId), "$.lockState")).isEqualTo("GRACE");

        setDueDate(contractId, SystemTime.today().minusDays(3));
        assertThat((String) JsonPath.read(getLock(customer, contractId), "$.lockState")).isEqualTo("LOCKED");
    }

    @Test
    void onlyTheOwnerSeesTheirCodeAndOnlyAdminsGetTheDeviceKey() throws Exception {
        String admin = loginAs("admin@tracom.local", "Admin@1234");
        String owner = registerAndLogin();
        String stranger = registerAndLogin();
        Long deviceId = createDevice(admin);
        Long contractId = approveContract(owner, admin, deviceId);

        mockMvc.perform(get("/api/v1/loans/{id}/device-lock", contractId)
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/devices/{id}/provisioning", deviceId)
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/devices/{id}/lock", deviceId)
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isForbidden());

        // The customer's view never carries the key or the code history.
        String body = getLock(owner, contractId);
        assertThat(body).doesNotContain("secret");
        assertThat(JsonPath.<java.util.List<?>>read(body, "$.history")).isEmpty();

        MvcResult staff = mockMvc.perform(get("/api/v1/admin/devices/{id}/lock", deviceId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(JsonPath.<java.util.List<?>>read(staff.getResponse().getContentAsString(), "$.history")).hasSize(1);
    }

    private String getLock(String token, Long contractId) throws Exception {
        return mockMvc.perform(get("/api/v1/loans/{id}/device-lock", contractId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String pay(String customerToken, Long contractId, String amount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractId\": %d, \"amount\": %s, \"channel\": \"MPESA\", \"mockScenario\": \"SUCCESS\"}"
                                .formatted(contractId, amount)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.transactionReference");
    }

    private void setDueDate(Long contractId, LocalDate due) {
        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        contract.setNextPaymentDueDate(due);
        loanContractRepository.save(contract);
    }

    private String registerAndLogin() throws Exception {
        String email = "lock-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\": \"Lock Customer\", \"email\": \"%s\", \"password\": \"Secret123\"}"
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
                                        String.format("355%012d", IMEI_SEQUENCE.incrementAndGet()),
                                        "LK-" + UUID.randomUUID().toString().replace("-", "")
                                                .substring(0, 10).toUpperCase())))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private Long approveContract(String customerToken, String officerToken, Long deviceId) throws Exception {
        Long planId = paymentPlanRepository.findAll().get(0).getId();
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
        MvcResult approved = mockMvc.perform(post("/api/v1/admin/financing/applications/{id}/approve", applicationId)
                        .header("Authorization", "Bearer " + officerToken))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(approved.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
