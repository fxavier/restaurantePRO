package com.xavier.backend.customers.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.exception.CustomersNotFoundException;
import com.xavier.backend.customers.model.Customer;
import com.xavier.backend.customers.model.CustomerAddress;
import com.xavier.backend.customers.model.CustomerPhone;
import com.xavier.backend.customers.model.CustomerQuery;
import com.xavier.backend.customers.model.PhoneNumber;
import com.xavier.backend.customers.model.dto.CustomerCandidate;
import com.xavier.backend.customers.model.dto.CustomerCreateRequest;
import com.xavier.backend.customers.model.dto.CustomerDetails;
import com.xavier.backend.customers.repository.CustomerAddressRepository;
import com.xavier.backend.customers.repository.CustomerPhoneRepository;
import com.xavier.backend.customers.repository.CustomerRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customers and their contacts (D72): {@code CUSTOMER_VIEW} to read and search, {@code CUSTOMER_MANAGE} to write. */
@Service
public class CustomerService {

    static final String ENTITY = "Customer";
    static final int MAX_CANDIDATES = 20;

    private final CustomerRepository customers;
    private final CustomerPhoneRepository phones;
    private final CustomerAddressRepository addresses;
    private final AccessPolicy accessPolicy;
    private final Clock clock;

    CustomerService(CustomerRepository customers, CustomerPhoneRepository phones, CustomerAddressRepository addresses,
                    AccessPolicy accessPolicy, Clock clock) {
        this.customers = customers;
        this.phones = phones;
        this.addresses = addresses;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    /** At most {@value #MAX_CANDIDATES} candidates, by name; never a selection (spec §5). */
    @Transactional(readOnly = true)
    public List<CustomerCandidate> search(Channel channel, String q) {
        channel.authorize(accessPolicy, Permission.CUSTOMER_VIEW);
        CustomerQuery query = CustomerQuery.of(q);
        List<Customer> found = switch (query.kind()) {
            case PHONE_SUFFIX -> customers.searchByPhoneSuffix(
                    new StringBuilder(query.text()).reverse() + "%", query.text(), MAX_CANDIDATES);
            case NAME -> customers.searchByName(escapeLike(query.text()), query.text(), MAX_CANDIDATES);
        };
        Map<UUID, List<String>> phonesOf = phones.findByCustomerIdInOrderByPrimaryDescDigitsAsc(
                        found.stream().map(Customer::getId).toList()).stream()
                .collect(Collectors.groupingBy(CustomerPhone::getCustomerId,
                        Collectors.mapping(CustomerPhone::getDigits, Collectors.toList())));
        return found.stream().map(c -> new CustomerCandidate(c.getId(), c.getName(), c.getTaxId(),
                phonesOf.getOrDefault(c.getId(), List.of()), c.isActive())).toList();
    }

    /** The text is matched literally: {@code %}, {@code _} and the escape character are not wildcards. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Transactional
    public CustomerDetails create(Channel channel, CustomerCreateRequest request) {
        channel.authorize(accessPolicy, Permission.CUSTOMER_MANAGE);
        Customer customer = customers.saveAndFlush(Customer.create(request.name(), request.taxId(), request.email(),
                request.notes(), clock.instant()));
        if (request.phone() != null) {
            phones.saveAndFlush(CustomerPhone.of(customer.getId(), PhoneNumber.of(request.phone()), null, true));
        }
        return details(customer);
    }

    @Transactional(readOnly = true)
    public CustomerDetails details(Channel channel, UUID id) {
        channel.authorize(accessPolicy, Permission.CUSTOMER_VIEW);
        return details(require(id));
    }

    Customer require(UUID id) {
        return customers.findById(id).orElseThrow(() -> new CustomersNotFoundException(ENTITY, id));
    }

    private CustomerDetails details(Customer customer) {
        return new CustomerDetails(customer.getId(), customer.getName(), customer.getTaxId(), customer.getEmail(),
                customer.getNotes(), customer.isActive(), customer.getVersion(),
                phones.findByCustomerIdOrderByPrimaryDescDigitsAsc(customer.getId()).stream()
                        .map(CustomerPhone::toView).toList(),
                addresses.findByCustomerIdOrderByIdAsc(customer.getId()).stream()
                        .map(CustomerAddress::toView).toList());
    }
}
