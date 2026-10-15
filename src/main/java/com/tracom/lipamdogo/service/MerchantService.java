package com.tracom.lipamdogo.service;

import com.tracom.lipamdogo.dto.MerchantRequest;
import com.tracom.lipamdogo.dto.MerchantResponse;
import com.tracom.lipamdogo.dto.MerchantUpdateRequest;
import com.tracom.lipamdogo.model.Merchant;
import com.tracom.lipamdogo.model.MerchantStatus;
import com.tracom.lipamdogo.model.Role;
import com.tracom.lipamdogo.model.UserAccount;
import com.tracom.lipamdogo.repository.AgentRepository;
import com.tracom.lipamdogo.repository.DeviceRepository;
import com.tracom.lipamdogo.repository.MerchantRepository;
import com.tracom.lipamdogo.repository.UserAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class MerchantService {

    private final MerchantRepository merchantRepository;
    private final AgentRepository agentRepository;
    private final DeviceRepository deviceRepository;
    private final UserAccountRepository userAccountRepository;

    public MerchantService(MerchantRepository merchantRepository,
                           AgentRepository agentRepository,
                           DeviceRepository deviceRepository,
                           UserAccountRepository userAccountRepository) {
        this.merchantRepository = merchantRepository;
        this.agentRepository = agentRepository;
        this.deviceRepository = deviceRepository;
        this.userAccountRepository = userAccountRepository;
    }

    @Transactional
    public MerchantResponse createMerchant(MerchantRequest request) {
        Merchant merchant = new Merchant();
        merchant.setMerchantCode(generateMerchantCode());
        merchant.setBusinessName(request.businessName().trim());
        merchant.setRegistrationNumber(blankToNull(request.registrationNumber()));
        merchant.setPhone(request.phone().trim());
        merchant.setEmail(request.email().trim().toLowerCase());
        merchant.setAddress(blankToNull(request.address()));
        merchant.setStatus(MerchantStatus.PENDING);
        if (request.ownerId() != null) {
            merchant.setOwner(requireMerchantCapableOwner(request.ownerId()));
        }
        return toResponse(merchantRepository.save(merchant));
    }

    @Transactional
    public MerchantResponse updateMerchant(Long id, MerchantUpdateRequest request) {
        Merchant merchant = requireMerchant(id);
        if (request.businessName() != null) {
            merchant.setBusinessName(request.businessName().trim());
        }
        if (request.registrationNumber() != null) {
            merchant.setRegistrationNumber(blankToNull(request.registrationNumber()));
        }
        if (request.phone() != null) {
            merchant.setPhone(request.phone().trim());
        }
        if (request.email() != null) {
            merchant.setEmail(request.email().trim().toLowerCase());
        }
        if (request.address() != null) {
            merchant.setAddress(blankToNull(request.address()));
        }
        if (request.status() != null) {
            merchant.setStatus(request.status());
        }
        if (request.ownerId() != null) {
            merchant.setOwner(requireMerchantCapableOwner(request.ownerId()));
        }
        merchant.setUpdatedAt(LocalDateTime.now());
        return toResponse(merchantRepository.save(merchant));
    }

    @Transactional(readOnly = true)
    public List<MerchantResponse> listMerchants(MerchantStatus status) {
        List<Merchant> merchants = status == null
                ? merchantRepository.findAll()
                : merchantRepository.findAllByStatus(status);
        return merchants.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public MerchantResponse getMerchant(Long id) {
        return toResponse(requireMerchant(id));
    }

    @Transactional(readOnly = true)
    public Merchant requireMerchant(Long id) {
        return merchantRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Merchant not found."));
    }

    @Transactional(readOnly = true)
    public List<MerchantResponse> merchantsForOwner(Long ownerId) {
        return merchantRepository.findAllByOwnerId(ownerId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Merchant requireMerchantForOwner(Long ownerId) {
        List<Merchant> owned = merchantRepository.findAllByOwnerId(ownerId);
        if (owned.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Merchant profile not found.");
        }
        return owned.get(0);
    }

    private MerchantResponse toResponse(Merchant merchant) {
        long agents = agentRepository.countByMerchantId(merchant.getId());
        long devices = deviceRepository.countByMerchantId(merchant.getId());
        return MerchantResponse.from(merchant, agents, devices);
    }

    private UserAccount requireMerchantCapableOwner(Long ownerId) {
        UserAccount owner = userAccountRepository.findById(ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Owner account not found."));
        if (owner.getRole() != Role.MERCHANT && owner.getRole() != Role.ADMIN) {
            throw new IllegalArgumentException("Owner account must hold the MERCHANT role.");
        }
        return owner;
    }

    private String generateMerchantCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = "MRC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
            if (!merchantRepository.existsByMerchantCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique merchant code.");
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
