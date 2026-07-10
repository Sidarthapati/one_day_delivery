package com.oneday.orders.repository;

import com.oneday.orders.domain.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    List<CartItem> findByCartIdOrderByCreatedAtAsc(UUID cartId);

    Optional<CartItem> findByIdAndCartId(UUID id, UUID cartId);

    long countByCartId(UUID cartId);
}
