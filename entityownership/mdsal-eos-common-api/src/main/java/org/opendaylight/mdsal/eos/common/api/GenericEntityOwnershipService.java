/*
 * Copyright (c) 2015 Brocade Communications Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.mdsal.eos.common.api;

import com.google.common.base.Optional;
import javax.annotation.Nonnull;
import org.opendaylight.yangtools.concepts.Path;

/**
 * <p>
 * An interface that provides the means for a component/application to request ownership for a given
 * Entity on the current cluster member. Entity ownership is always tied to a process and two components on the same
 * process cannot register a candidate for a given Entity.
 * </p>
 * <p>
 * A component/application may also register interest in the ownership status of an Entity. The listener would be
 * notified whenever the ownership status changes.
 * </p>
 *
 * @author Thomas Pantelis
 *
 * @param <P> the instance identifier path type
 * @param <E> the GenericEntity type
 */
// 一种接口，为组件/应用程序提供在当前集群成员上请求给定实体的所有权的方法
// 实体所有权始终与进程相关联，同一进程上的两个组件无法同时注册给定实体的候选者
public interface GenericEntityOwnershipService<P extends Path<P>, E extends GenericEntity<P>,
        L extends GenericEntityOwnershipListener<P, ? extends GenericEntityOwnershipChange<P, E>>> {

    /**
     * Registers a candidate for ownership of the given entity. Only one such request can be made per entity
     * per process. If multiple requests for registering a candidate for a given entity are received in the
     * current process a CandidateAlreadyRegisteredException will be thrown.
     *
     * <p>
     * The registration is performed asynchronously and any registered entity ownership listener is
     * notified of ownership status changes for the entity.
     *
     * @param entity the entity which the Candidate wants to own
     * @return a registration object that can be used to unregister the Candidate
     * @throws CandidateAlreadyRegisteredException if the candidate was already registered
     */
    // 注册为entity的 candidate
    GenericEntityOwnershipCandidateRegistration<P, E> registerCandidate(@Nonnull E entity)
            throws CandidateAlreadyRegisteredException;

    /**
     * Registers a listener that is interested in ownership changes for entities of the given entity type. The
     * listener is notified whenever its process instance is granted ownership of the entity and also whenever
     * it loses ownership. On registration the listener will be notified of all entities its process instance
     * currently owns at the time of registration.
     *
     * @param entityType the type of entities whose ownership status the Listener is interested in
     * @param listener the listener that is interested in the entities
     * @return a registration object that can be used to unregister the Listener
     */
    // 注册某个类型的entity(openflow/netconf) ownership状态改变的listener
    GenericEntityOwnershipListenerRegistration<P, L> registerListener(@Nonnull String entityType, @Nonnull L listener);

    /**
     * Gets the current ownership state information for an entity.
     *
     * @param forEntity the entity to query.
     * @return an Optional EntityOwnershipState whose instance is present if the entity is found
     */
    // 获取某个entity ownership状态
    Optional<EntityOwnershipState> getOwnershipState(@Nonnull E forEntity);

    /**
     * Checks if a local candidate is registered for the given entity.
     *
     * @param forEntity the entity to query.
     * @return true if a candidate is registered locally, false otherwise
     */
    // 检查本地是否注册了entity的candidate选举
    boolean isCandidateRegistered(@Nonnull E forEntity);
}
