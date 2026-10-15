package com.tracom.lipamdogo.service;

import com.tracom.lipamdogo.dto.DeviceAssignRequest;
import com.tracom.lipamdogo.dto.DeviceRequest;
import com.tracom.lipamdogo.dto.DeviceResponse;
import com.tracom.lipamdogo.dto.DeviceStatusRequest;
import com.tracom.lipamdogo.model.Agent;
import com.tracom.lipamdogo.model.AgentStatus;
import com.tracom.lipamdogo.model.Device;
import com.tracom.lipamdogo.model.DeviceStatus;
import com.tracom.lipamdogo.model.Merchant;
import com.tracom.lipamdogo.model.MerchantStatus;
import com.tracom.lipamdogo.model.Product;
import com.tracom.lipamdogo.model.UserAccount;
import com.tracom.lipamdogo.repository.AgentRepository;
import com.tracom.lipamdogo.repository.DeviceRepository;
import com.tracom.lipamdogo.repository.MerchantRepository;
import com.tracom.lipamdogo.repository.ProductRepository;
import com.tracom.lipamdogo.repository.UserAccountRepository;
import com.tracom.lipamdogo.support.SystemTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Single home for every physical-device state change, so the future audit
 * module has one reliable hook point. Status moves are validated against
 * {@link #TRANSITIONS}; relationship changes only happen in the assignment
 * operations below.
 */
@Service
public class DeviceService {

    private static final Map<DeviceStatus, Set<DeviceStatus>> TRANSITIONS = new EnumMap<>(DeviceStatus.class);

    static {
        TRANSITIONS.put(DeviceStatus.AVAILABLE, EnumSet.of(DeviceStatus.RESERVED, DeviceStatus.ASSIGNED, DeviceStatus.RETIRED));
        TRANSITIONS.put(DeviceStatus.RESERVED, EnumSet.of(DeviceStatus.AVAILABLE, DeviceStatus.ASSIGNED, DeviceStatus.FINANCED, DeviceStatus.RETIRED));
        TRANSITIONS.put(DeviceStatus.ASSIGNED, EnumSet.of(DeviceStatus.FINANCED, DeviceStatus.AVAILABLE, DeviceStatus.REPAIR, DeviceStatus.LOST, DeviceStatus.RETIRED));
        TRANSITIONS.put(DeviceStatus.FINANCED, EnumSet.of(DeviceStatus.OVERDUE, DeviceStatus.PAID_OFF, DeviceStatus.REPAIR, DeviceStatus.LOST));
        TRANSITIONS.put(DeviceStatus.OVERDUE, EnumSet.of(DeviceStatus.FINANCED, DeviceStatus.PAID_OFF, DeviceStatus.REPAIR, DeviceStatus.LOST));
        TRANSITIONS.put(DeviceStatus.PAID_OFF, EnumSet.noneOf(DeviceStatus.class));
        TRANSITIONS.put(DeviceStatus.REPAIR, EnumSet.of(DeviceStatus.AVAILABLE, DeviceStatus.ASSIGNED, DeviceStatus.RETIRED));
        TRANSITIONS.put(DeviceStatus.LOST, EnumSet.of(DeviceStatus.RETIRED));
        TRANSITIONS.put(DeviceStatus.RETIRED, EnumSet.noneOf(DeviceStatus.class));
    }

    private final DeviceRepository deviceRepository;
    private final ProductRepository productRepository;
    private final MerchantRepository merchantRepository;
    private final AgentRepository agentRepository;
    private final UserAccountRepository userAccountRepository;

    public DeviceService(DeviceRepository deviceRepository,
                         ProductRepository productRepository,
                         MerchantRepository merchantRepository,
                         AgentRepository agentRepository,
                         UserAccountRepository userAccountRepository) {
        this.deviceRepository = deviceRepository;
        this.productRepository = productRepository;
        this.merchantRepository = merchantRepository;
        this.agentRepository = agentRepository;
        this.userAccountRepository = userAccountRepository;
    }

    @Transactional
    public DeviceResponse createDevice(DeviceRequest request) {
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new IllegalArgumentException("Product not found with id: " + request.productId()));

        String imei = blankToNull(request.imei());
        if (imei != null && deviceRepository.existsByImei(imei)) {
            throw new IllegalArgumentException("A device with this IMEI already exists.");
        }
        String serial = blankToNull(request.serialNumber());
        if (serial != null && deviceRepository.existsBySerialNumber(serial)) {
            throw new IllegalArgumentException("A device with this serial number already exists.");
        }

        Device device = new Device();
        device.setDeviceCode(generateDeviceCode());
        device.setProduct(product);
        device.setImei(imei);
        device.setSerialNumber(serial);
        device.setStatus(DeviceStatus.AVAILABLE);
        return DeviceResponse.from(deviceRepository.save(device));
    }

    /**
     * Assigns a device to exactly one target. Customer assignment moves
     * ASSIGNED → FINANCED; merchant/agent assignment moves the unit into the
     * channel as ASSIGNED.
     */
    @Transactional
    public DeviceResponse assignDevice(Long deviceId, DeviceAssignRequest request) {
        boolean wantsMerchant = request.merchantId() != null;
        boolean wantsAgent = request.agentId() != null;
        boolean wantsCustomer = request.customerId() != null;
        if ((wantsMerchant ? 1 : 0) + (wantsAgent ? 1 : 0) + (wantsCustomer ? 1 : 0) != 1) {
            throw new IllegalArgumentException("Assignment requires exactly one of merchantId, agentId or customerId.");
        }

        Device device = requireDevice(deviceId);
        if (wantsCustomer) {
            if (device.getStatus() != DeviceStatus.ASSIGNED) {
                throw new IllegalArgumentException("Only a channel-assigned device can be handed to a customer.");
            }
            UserAccount customer = userAccountRepository.findById(request.customerId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer account not found."));
            device.setCustomer(customer);
            device.setAgent(null);
            move(device, DeviceStatus.FINANCED);
        } else if (wantsAgent) {
            requireReassignable(device);
            Agent agent = agentRepository.findById(request.agentId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found."));
            if (agent.getStatus() != AgentStatus.ACTIVE) {
                throw new IllegalArgumentException("Devices can only be assigned to an ACTIVE agent.");
            }
            requireOperatingMerchant(agent.getMerchant());
            device.setAgent(agent);
            device.setMerchant(agent.getMerchant());
            device.setCustomer(null);
            move(device, DeviceStatus.ASSIGNED);
        } else {
            requireReassignable(device);
            Merchant merchant = merchantRepository.findById(request.merchantId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Merchant not found."));
            requireOperatingMerchant(merchant);
            device.setMerchant(merchant);
            device.setAgent(null);
            device.setCustomer(null);
            move(device, DeviceStatus.ASSIGNED);
        }
        device.setUpdatedAt(SystemTime.now());
        return DeviceResponse.from(deviceRepository.save(device));
    }

    /**
     * Returns a channel device to the warehouse: relationships cleared, back to
     * AVAILABLE. Only meaningful from ASSIGNED or REPAIR.
     */
    @Transactional
    public DeviceResponse releaseDevice(Long deviceId) {
        Device device = requireDevice(deviceId);
        if (device.getStatus() != DeviceStatus.ASSIGNED && device.getStatus() != DeviceStatus.REPAIR) {
            throw new IllegalArgumentException("Only ASSIGNED or REPAIR devices can be released to inventory.");
        }
        device.setMerchant(null);
        device.setAgent(null);
        device.setCustomer(null);
        move(device, DeviceStatus.AVAILABLE);
        device.setUpdatedAt(SystemTime.now());
        return DeviceResponse.from(deviceRepository.save(device));
    }

    /**
     * Financing reservation: AVAILABLE → RESERVED. Called by the financing
     * workflow when an application is submitted; sets no relationships.
     */
    @Transactional
    public DeviceResponse reserveForFinancing(Long deviceId) {
        Device device = requireDevice(deviceId);
        if (device.getStatus() != DeviceStatus.AVAILABLE) {
            throw new IllegalArgumentException("Only AVAILABLE devices can be reserved for financing.");
        }
        move(device, DeviceStatus.RESERVED);
        device.setUpdatedAt(SystemTime.now());
        return DeviceResponse.from(deviceRepository.save(device));
    }

    /**
     * Releases a financing reservation: RESERVED → AVAILABLE. Used when an
     * application is cancelled or rejected. No relationships to clear, since
     * reservations never set any.
     */
    @Transactional
    public DeviceResponse releaseReservation(Long deviceId) {
        Device device = requireDevice(deviceId);
        if (device.getStatus() != DeviceStatus.RESERVED) {
            throw new IllegalArgumentException("Only RESERVED devices can have their reservation released.");
        }
        move(device, DeviceStatus.AVAILABLE);
        device.setUpdatedAt(SystemTime.now());
        return DeviceResponse.from(deviceRepository.save(device));
    }

    /**
     * Hands the unit to the financed customer: RESERVED (warehouse-direct) or
     * ASSIGNED (channel-held) → FINANCED. The customer is set, any channel
     * agent is cleared, and the merchant link is kept as provenance.
     */
    @Transactional
    public DeviceResponse handoverToCustomer(Long deviceId, Long customerId) {
        Device device = requireDevice(deviceId);
        if (device.getStatus() != DeviceStatus.RESERVED && device.getStatus() != DeviceStatus.ASSIGNED) {
            throw new IllegalArgumentException("Only RESERVED or ASSIGNED devices can be handed to a customer.");
        }
        UserAccount customer = userAccountRepository.findById(customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer account not found."));
        device.setCustomer(customer);
        device.setAgent(null);
        move(device, DeviceStatus.FINANCED);
        device.setUpdatedAt(SystemTime.now());
        return DeviceResponse.from(deviceRepository.save(device));
    }

    /**
     * Arrears transitions driven by the contract (never by staff input):
     * FINANCED → OVERDUE when the customer falls behind, OVERDUE → FINANCED
     * once they catch up. Relationships (customer, merchant) are unchanged.
     */
    @Transactional
    public DeviceResponse markInArrears(Long deviceId) {
        Device device = requireDevice(deviceId);
        if (device.getStatus() != DeviceStatus.FINANCED) {
            throw new IllegalArgumentException("Only FINANCED devices can move into arrears.");
        }
        move(device, DeviceStatus.OVERDUE);
        device.setUpdatedAt(SystemTime.now());
        return DeviceResponse.from(deviceRepository.save(device));
    }

    @Transactional
    public DeviceResponse clearArrears(Long deviceId) {
        Device device = requireDevice(deviceId);
        if (device.getStatus() != DeviceStatus.OVERDUE) {
            throw new IllegalArgumentException("Only OVERDUE devices can return to FINANCED.");
        }
        move(device, DeviceStatus.FINANCED);
        device.setUpdatedAt(SystemTime.now());
        return DeviceResponse.from(deviceRepository.save(device));
    }

    /**
     * Relationship-free status moves (reserve, repair, lost, retire, paid off,
     * overdue, …). ASSIGNED and FINANCED are rejected here: they require the
     * assignment endpoint so relationships stay consistent.
     */
    @Transactional
    public DeviceResponse changeStatus(Long deviceId, DeviceStatusRequest request) {
        if (request.status() == DeviceStatus.ASSIGNED || request.status() == DeviceStatus.FINANCED) {
            throw new IllegalArgumentException("ASSIGNED and FINANCED require the assignment operation.");
        }
        Device device = requireDevice(deviceId);
        move(device, request.status());
        device.setUpdatedAt(SystemTime.now());
        return DeviceResponse.from(deviceRepository.save(device));
    }

    @Transactional(readOnly = true)
    public DeviceResponse getDevice(Long deviceId) {
        return DeviceResponse.from(requireDevice(deviceId));
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> listDevices(DeviceStatus status, Long productId,
                                            Long merchantId, Long agentId, Long customerId) {
        List<Device> devices;
        if (customerId != null) {
            devices = deviceRepository.findAllByCustomerId(customerId);
        } else if (agentId != null) {
            devices = deviceRepository.findAllByAgentId(agentId);
        } else if (merchantId != null) {
            devices = status == null
                    ? deviceRepository.findAllByMerchantId(merchantId)
                    : deviceRepository.findAllByMerchantIdAndStatus(merchantId, status);
        } else if (productId != null) {
            devices = deviceRepository.findAllByProductId(productId);
        } else if (status != null) {
            devices = deviceRepository.findAllByStatus(status);
        } else {
            devices = deviceRepository.findAll();
        }
        return devices.stream().map(DeviceResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public Device requireDevice(Long deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found."));
    }

    private void move(Device device, DeviceStatus target) {
        if (device.getStatus() == target) {
            return;
        }
        Set<DeviceStatus> allowed = TRANSITIONS.getOrDefault(device.getStatus(), Set.of());
        if (!allowed.contains(target)) {
            throw new IllegalArgumentException(
                    "Illegal device transition: " + device.getStatus() + " -> " + target + ".");
        }
        device.setStatus(target);
    }

    private void requireReassignable(Device device) {
        if (device.getStatus() != DeviceStatus.AVAILABLE
                && device.getStatus() != DeviceStatus.RESERVED
                && device.getStatus() != DeviceStatus.REPAIR) {
            throw new IllegalArgumentException(
                    "Only AVAILABLE, RESERVED or REPAIR devices can be assigned to a channel.");
        }
    }

    private void requireOperatingMerchant(Merchant merchant) {
        if (merchant.getStatus() != MerchantStatus.ACTIVE) {
            throw new IllegalArgumentException("Devices can only be assigned under an ACTIVE merchant.");
        }
    }

    private String generateDeviceCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = "TRC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
            if (!deviceRepository.existsByDeviceCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique device code.");
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
