package com.tracom.lipamdogo;

import com.jayway.jsonpath.JsonPath;
import com.tracom.lipamdogo.model.DeviceStatus;
import com.tracom.lipamdogo.model.LoanContract;
import com.tracom.lipamdogo.model.LoanStatus;
import com.tracom.lipamdogo.repository.DeviceRepository;
import com.tracom.lipamdogo.repository.LoanContractRepository;
import com.tracom.lipamdogo.repository.PaymentPlanRepository;
import com.tracom.lipamdogo.repository.ProductRepository;
import com.tracom.lipamdogo.support.SystemTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-of-day arrears sweep: a customer who stops paying must become OVERDUE
 * (with the device following) even though no payment event happens, and must
 * return to ACTIVE/FINANCED once they catch up. The sweep never moves money.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ArrearsSweepIntegrationTest {

    private static final AtomicLong IMEI_SEQUENCE = new AtomicLong(910000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PaymentPlanRepository paymentPlanRepository;

    @Autowired
    private LoanContractRepository loanContractRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Test
    void customerWhoStopsPayingBecomesOverdueAndRecoversAfterCatchingUp() throws Exception {
        String admin = loginAs("admin@tracom.local", "Admin@1234");
        String customer = registerAndLogin();
        Long deviceId = createDevice(admin);
        Long contractId = approveContract(customer, admin, deviceId);

        // Simulate four days passing with no payments: the first installment
        // fell due three days ago.
        LoanContract contract = loanContractRepository.findById(contractId).orElseThrow();
        contract.setNextPaymentDueDate(SystemTime.today().minusDays(3));
        loanContractRepository.save(contract);
        BigDecimal remainingBefore = contract.getAmountRemaining();

        MvcResult sweep = mockMvc.perform(post("/api/v1/admin/operations/arrears-sweep")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        assertThat((Integer) JsonPath.read(sweep.getResponse().getContentAsString(), "$.newlyOverdue"))
                .isGreaterThanOrEqualTo(1);

        contract = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(contract.getStatus()).isEqualTo(LoanStatus.OVERDUE);
        assertThat(contract.getAmountRemaining()).isEqualByComparingTo(remainingBefore);
        assertThat(deviceRepository.findById(deviceId).orElseThrow().getStatus()).isEqualTo(DeviceStatus.OVERDUE);

        // Running it again is a no-op for this contract.
        mockMvc.perform(post("/api/v1/admin/operations/arrears-sweep")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        assertThat(loanContractRepository.findById(contractId).orElseThrow().getStatus())
                .isEqualTo(LoanStatus.OVERDUE);

        // Paying the four missed installments brings the contract current.
        BigDecimal catchUp = contract.getDailyAmount().multiply(BigDecimal.valueOf(4));
        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + customer)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractId\": %d, \"amount\": %s, \"channel\": \"MPESA\", \"mockScenario\": \"SUCCESS\"}"
                                .formatted(contractId, catchUp.toPlainString())))
                .andExpect(status().isCreated());

        contract = loanContractRepository.findById(contractId).orElseThrow();
        assertThat(contract.getStatus()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(deviceRepository.findById(deviceId).orElseThrow().getStatus()).isEqualTo(DeviceStatus.FINANCED);
    }

    @Test
    void customersCannotRunTheSweep() throws Exception {
        String customer = registerAndLogin();
        mockMvc.perform(post("/api/v1/admin/operations/arrears-sweep")
                        .header("Authorization", "Bearer " + customer))
                .andExpect(status().isForbidden());
    }

    private String registerAndLogin() throws Exception {
        String email = "arrears-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\": \"Arrears Customer\", \"email\": \"%s\", \"password\": \"Secret123\"}"
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
        Long productId = productRepository.findAll().get(0).getId();
        MvcResult created = mockMvc.perform(post("/api/v1/admin/devices")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": %d, \"imei\": \"%s\", \"serialNumber\": \"%s\"}"
                                .formatted(productId,
                                        String.format("353%012d", IMEI_SEQUENCE.incrementAndGet()),
                                        "AR-" + UUID.randomUUID().toString().replace("-", "")
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
