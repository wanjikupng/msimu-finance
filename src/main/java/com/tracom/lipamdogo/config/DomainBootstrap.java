package com.tracom.lipamdogo.config;

import com.tracom.lipamdogo.model.Agent;
import com.tracom.lipamdogo.model.AgentStatus;
import com.tracom.lipamdogo.model.Device;
import com.tracom.lipamdogo.model.DeviceStatus;
import com.tracom.lipamdogo.model.Merchant;
import com.tracom.lipamdogo.model.MerchantStatus;
import com.tracom.lipamdogo.model.Product;
import com.tracom.lipamdogo.model.Role;
import com.tracom.lipamdogo.model.UserAccount;
import com.tracom.lipamdogo.repository.AgentRepository;
import com.tracom.lipamdogo.repository.DeviceRepository;
import com.tracom.lipamdogo.repository.MerchantRepository;
import com.tracom.lipamdogo.repository.ProductRepository;
import com.tracom.lipamdogo.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Development/demo seed for the Phase 2 domain: merchant owners, agents,
 * merchants, and a small physical-device inventory in representative states.
 *
 * <p>Runs after {@link IdentityBootstrap} and only when the domain tables are
 * empty. All credentials are BCrypt-hashed; defaults are academy/demo only and
 * overridable via environment variables.</p>
 */
@Component
@Order(2)
public class DomainBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DomainBootstrap.class);

    private final UserAccountRepository userAccountRepository;
    private final MerchantRepository merchantRepository;
    private final AgentRepository agentRepository;
    private final DeviceRepository deviceRepository;
    private final ProductRepository productRepository;
    private final PasswordEncoder passwordEncoder;
    private final String agentPassword;
    private final String merchantPassword;

    public DomainBootstrap(UserAccountRepository userAccountRepository,
                           MerchantRepository merchantRepository,
                           AgentRepository agentRepository,
                           DeviceRepository deviceRepository,
                           ProductRepository productRepository,
                           PasswordEncoder passwordEncoder,
                           @Value("${app.bootstrap.agent.password:Agent@1234}") String agentPassword,
                           @Value("${app.bootstrap.merchant.password:Merchant@1234}") String merchantPassword) {
        this.userAccountRepository = userAccountRepository;
        this.merchantRepository = merchantRepository;
        this.agentRepository = agentRepository;
        this.deviceRepository = deviceRepository;
        this.productRepository = productRepository;
        this.passwordEncoder = passwordEncoder;
        this.agentPassword = agentPassword;
        this.merchantPassword = merchantPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (merchantRepository.count() > 0 || agentRepository.count() > 0 || deviceRepository.count() > 0) {
            return;
        }

        UserAccount quickMartOwner = ensureUser("QuickMart Owner", "quickmart@tracom.local",
                "+254700000101", merchantPassword, Role.MERCHANT, "C-MRCQ01");
        UserAccount techPointOwner = ensureUser("TechPoint Owner", "techpoint@tracom.local",
                "+254700000102", merchantPassword, Role.MERCHANT, "C-MRCT01");
        UserAccount amina = ensureUser("Amina Yusuf", "amina.agent@tracom.local",
                "+254700000111", agentPassword, Role.AGENT, "C-AGTAM1");
        UserAccount brian = ensureUser("Brian Otieno", "brian.agent@tracom.local",
                "+254700000112", agentPassword, Role.AGENT, "C-AGTBR1");
        UserAccount faith = ensureUser("Faith Njeri", "faith.agent@tracom.local",
                "+254700000113", agentPassword, Role.AGENT, "C-AGTFA1");

        Merchant quickMart = ensureMerchant("MRC-0001", "QuickMart Electronics", "BRN-2024-1188",
                "+254700000201", "sales@quickmart.example.com", "Tom Mboya Street, Nairobi",
                MerchantStatus.ACTIVE, quickMartOwner);
        Merchant techPoint = ensureMerchant("MRC-0002", "TechPoint", null,
                "+254700000202", "hello@techpoint.example.com", "Moi Avenue, Nairobi",
                MerchantStatus.ACTIVE, techPointOwner);

        Agent aminaAgent = ensureAgent("AGT-00001", amina, quickMart);
        ensureAgent("AGT-00002", brian, quickMart);
        Agent faithAgent = ensureAgent("AGT-00003", faith, techPoint);

        UserAccount demoCustomer = userAccountRepository.findByEmail("demo@lipamdogo.com").orElse(null);

        Product a15 = productRepository.findByName("Samsung Galaxy A15").orElseThrow();
        Product redmi = productRepository.findByName("Redmi 14C").orElseThrow();
        Product spark = productRepository.findByName("Tecno Spark 10").orElseThrow();

        ensureDevice("TRC-000129", "356938035643809", "TRC-SAM-A15-000129", a15,
                DeviceStatus.AVAILABLE, null, null, null);
        ensureDevice("TRC-000130", "356938035643817", "TRC-SAM-A15-000130", a15,
                DeviceStatus.ASSIGNED, quickMart, aminaAgent, null);
        ensureDevice("TRC-000131", "356938035643825", "TRC-SAM-A15-000131", a15,
                DeviceStatus.FINANCED, quickMart, null, demoCustomer);
        ensureDevice("TRC-000132", "867648022113452", "TRC-RED-14C-000132", redmi,
                DeviceStatus.AVAILABLE, null, null, null);
        ensureDevice("TRC-000133", "867648022113460", "TRC-RED-14C-000133", redmi,
                DeviceStatus.REPAIR, null, null, null);
        ensureDevice("TRC-000134", "354411098765431", "TRC-TSP-010-000134", spark,
                DeviceStatus.AVAILABLE, null, null, null);
        ensureDevice("TRC-000135", "354411098765449", "TRC-TSP-010-000135", spark,
                DeviceStatus.ASSIGNED, techPoint, faithAgent, null);

        log.info("Domain seed ready: 2 merchants, 3 agents, 7 devices.");
    }

    private UserAccount ensureUser(String fullName, String email, String phone,
                                   String rawPassword, Role role, String customerCode) {
        return userAccountRepository.findByEmail(email).orElseGet(() -> {
            UserAccount account = new UserAccount();
            account.setFullName(fullName);
            account.setEmail(email);
            account.setPhoneNumber(phone);
            account.setPassword(passwordEncoder.encode(rawPassword));
            account.setRole(role);
            account.setCustomerCode(customerCode);
            return userAccountRepository.save(account);
        });
    }

    private Merchant ensureMerchant(String code, String businessName, String registrationNumber,
                                    String phone, String email, String address,
                                    MerchantStatus status, UserAccount owner) {
        Merchant merchant = new Merchant();
        merchant.setMerchantCode(code);
        merchant.setBusinessName(businessName);
        merchant.setRegistrationNumber(registrationNumber);
        merchant.setPhone(phone);
        merchant.setEmail(email);
        merchant.setAddress(address);
        merchant.setStatus(status);
        merchant.setOwner(owner);
        return merchantRepository.save(merchant);
    }

    private Agent ensureAgent(String code, UserAccount account, Merchant merchant) {
        Agent agent = new Agent();
        agent.setAgentCode(code);
        agent.setUserAccount(account);
        agent.setMerchant(merchant);
        agent.setStatus(AgentStatus.ACTIVE);
        return agentRepository.save(agent);
    }

    private void ensureDevice(String code, String imei, String serial, Product product,
                              DeviceStatus status, Merchant merchant, Agent agent, UserAccount customer) {
        Device device = new Device();
        device.setDeviceCode(code);
        device.setImei(imei);
        device.setSerialNumber(serial);
        device.setProduct(product);
        device.setStatus(status);
        device.setMerchant(merchant);
        device.setAgent(agent);
        device.setCustomer(customer);
        deviceRepository.save(device);
    }
}
