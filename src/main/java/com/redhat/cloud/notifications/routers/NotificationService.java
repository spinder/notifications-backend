package com.redhat.cloud.notifications.routers;

import com.redhat.cloud.notifications.Constants;
import com.redhat.cloud.notifications.auth.rbac.RbacIdentityProvider;
import com.redhat.cloud.notifications.auth.rhid.RhIdPrincipal;
import com.redhat.cloud.notifications.db.ApplicationResources;
import com.redhat.cloud.notifications.db.BehaviorGroupResources;
import com.redhat.cloud.notifications.db.BundleResources;
import com.redhat.cloud.notifications.db.EndpointResources;
import com.redhat.cloud.notifications.db.Query;
import com.redhat.cloud.notifications.models.BehaviorGroup;
import com.redhat.cloud.notifications.models.BehaviorGroupAction;
import com.redhat.cloud.notifications.models.Endpoint;
import com.redhat.cloud.notifications.models.EndpointType;
import com.redhat.cloud.notifications.models.EventType;
import com.redhat.cloud.notifications.routers.models.Facet;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.BeanParam;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN;
import static javax.ws.rs.core.Response.Status;

@Path(Constants.API_NOTIFICATIONS_V_1_0 + "/notifications")
public class NotificationService {

    @Inject
    Vertx vertx;

    @Inject
    BundleResources bundleResources;

    @Inject
    ApplicationResources apps;

    @Inject
    EndpointResources resources;

    @Inject
    BehaviorGroupResources behaviorGroupResources;

    @Inject
    EndpointResources endpointResources;

    private Uni<String> getAccountId(SecurityContext sec) {
        return Uni.createFrom().item(() -> {
            RhIdPrincipal principal = (RhIdPrincipal) sec.getUserPrincipal();
            return principal.getAccount();
        });
    }

// We do not yet use this API yet and the return type is polluting the openapi with "internal details" because we are
// directly using an avro generated code.
//    @GET
//    @Produces(MediaType.SERVER_SENT_EVENTS)
//    @Path("/updates")
//    public Multi<Notification> getNotificationUpdates(@Context SecurityContext sec) {
//        // TODO Check the Notification type if we want something else
//        // TODO Process:
//        //      - Fetch the last unread notifications (with some limit?)
//        //         - If we're not previously subscribed, add the last n-days of notifications to our unread list?
//        //          - Add subscription to our subscription base to receive future notifications
//        //      - Subscribe to VertX eventBus listening for: notifications<tenantId><userId>
//        return vertx.eventBus().consumer(getAddress(sec.getUserPrincipal()))
//                .toMulti()
//                // TODO Verify that toMulti subscribes to a hot stream, not cold!
//                .onItem()
//                .transform(m -> (Notification) m.body());
//    }

    @DELETE
    @Path("/{id}")
    @Produces(TEXT_PLAIN)
    @APIResponse(responseCode = "204", description = "Notification has been marked as read", content = @Content(schema = @Schema(type = SchemaType.STRING)))
    public Uni<Response> markRead(@Context SecurityContext sec, @PathParam("id") Integer id) {
        // Mark the notification id for <tenantId><userId> 's subscription as read
        return Uni.createFrom().nullItem();
    }

    // TODO Mark all as read?

    // TODO DB structure? <tenantId><userId><notificationId><read> ? Will we show old read-messages or not? Do we vacuum old items from the subscriptions?

    private String getAddress(Principal principal) {
        // TODO This should call some global point which is used by the Processor interface to push data to the same queue names
        RhIdPrincipal rhUser = (RhIdPrincipal) principal;
        return String.format("notifications-%s", rhUser.getAccount());
    }

    // Event type linking

    @GET
    @Path("/eventTypes")
    @Produces(APPLICATION_JSON)
    @Operation(summary = "Retrieve all event types. The returned list can be filtered by bundle or application.")
    @RolesAllowed(RbacIdentityProvider.RBAC_READ_NOTIFICATIONS)
    public Uni<List<EventType>> getEventTypes(@BeanParam Query query, @QueryParam("applicationIds") Set<UUID> applicationIds, @QueryParam("bundleId") UUID bundleId) {
        return apps.getEventTypes(query, applicationIds, bundleId);
    }

    // TODO [BG Phase 2] Delete this method
    @GET
    @Path("/eventTypes/affectedByRemovalOfEndpoint/{endpointId}")
    @Produces(APPLICATION_JSON)
    @RolesAllowed(RbacIdentityProvider.RBAC_READ_NOTIFICATIONS)
    public Uni<List<EventType>> getEventTypesAffectedByEndpointId(@Context SecurityContext sec, @PathParam("endpointId") UUID endpointId) {
        RhIdPrincipal principal = (RhIdPrincipal) sec.getUserPrincipal();

        Multi<EventType> directlyAffected = apps.getEventTypesByEndpointId(principal.getAccount(), endpointId)
                .onItem().transformToMulti(Multi.createFrom()::iterable);
        Multi<EventType> indirectlyAffected = resources.getEndpointsPerType(principal.getAccount(), EndpointType.DEFAULT, null, null)
                .onItem().transformToMulti(Multi.createFrom()::iterable)
                .toUni().onItem().transformToMulti(defaultEndpoint ->
            resources.endpointInDefaults(principal.getAccount(), endpointId)
                    .onItem().transformToMulti(exists -> {
                        if (exists) {
                            return apps.getEventTypesByEndpointId(principal.getAccount(), defaultEndpoint.getId())
                                    .onItem().transformToMulti(Multi.createFrom()::iterable);
                        }

                        return Multi.createFrom().empty();
                    })
        );

        return Multi.createBy().concatenating().streams(directlyAffected, indirectlyAffected)
                .collect().asList();
    }

    /*
     * Called by the UI to build the endpoint removal confirmation screen.
     * That screen shows all the event types (and their application) that will be affected by the endpoint removal.
     */
    @GET
    @Path("/bg/eventTypes/affectedByRemovalOfEndpoint/{endpointId}") // TODO [BG Phase 2] Remove '/bg' path prefix
    @Produces(APPLICATION_JSON)
    @Operation(summary = "Retrieve the event types affected by the removal of an integration.", hidden = true)
    @RolesAllowed(RbacIdentityProvider.RBAC_READ_NOTIFICATIONS)
    public Uni<List<EventType>> getEventTypesAffectedByRemovalOfEndpoint(@Context SecurityContext sec, @PathParam("endpointId") UUID endpointId) {
        return getAccountId(sec)
                .onItem().transformToUni(accountId -> apps.getEventTypesByEndpointId_BG(accountId, endpointId));
    }

    /*
     * Called by the UI to build the behavior group removal confirmation screen.
     * That screen shows all the event types (and their application) that will be affected by the behavior group removal.
     */
    @GET
    @Path("/eventTypes/affectedByRemovalOfBehaviorGroup/{behaviorGroupId}")
    @Produces(APPLICATION_JSON)
    @Operation(summary = "Retrieve the event types affected by the removal of a behavior group.", hidden = true)
    @RolesAllowed(RbacIdentityProvider.RBAC_READ_NOTIFICATIONS)
    public Uni<List<EventType>> getEventTypesAffectedByRemovalOfBehaviorGroup(@Context SecurityContext sec, @PathParam("behaviorGroupId") UUID behaviorGroupId) {
        return getAccountId(sec)
                .onItem().transformToUni(accountId -> behaviorGroupResources.findEventTypesByBehaviorGroupId(accountId, behaviorGroupId));
    }

    // TODO [BG Phase 2] Delete this method
    @PUT
    @Path("/eventTypes/{eventTypeId}/{endpointId}")
    @Produces(TEXT_PLAIN)
    @RolesAllowed(RbacIdentityProvider.RBAC_WRITE_NOTIFICATIONS)
    @APIResponse(responseCode = "200", content = @Content(schema = @Schema(type = SchemaType.STRING)))
    public Uni<Response> linkEndpointToEventType(@Context SecurityContext sec, @PathParam("endpointId") UUID endpointId, @PathParam("eventTypeId") UUID eventTypeId) {
        RhIdPrincipal principal = (RhIdPrincipal) sec.getUserPrincipal();
        return resources.linkEndpoint(principal.getAccount(), endpointId, eventTypeId)
                .onItem().transform(ignored -> Response.ok().build());
    }

    // TODO [BG Phase 2] Delete this method
    @DELETE
    @Path("/eventTypes/{eventTypeId}/{endpointId}")
    @RolesAllowed(RbacIdentityProvider.RBAC_WRITE_NOTIFICATIONS)
    @APIResponse(responseCode = "204", description = "Integration has been removed from the event type", content = @Content(schema = @Schema(type = SchemaType.STRING)))
    public Uni<Response> unlinkEndpointFromEventType(@Context SecurityContext sec, @PathParam("endpointId") UUID endpointId, @PathParam("eventTypeId") UUID eventTypeId) {
        RhIdPrincipal principal = (RhIdPrincipal) sec.getUserPrincipal();
        return resources.unlinkEndpoint(principal.getAccount(), endpointId, eventTypeId)
                .onItem().transform(ignored -> Response.noContent().build());
    }

    // TODO [BG Phase 2] Delete this method
    @GET
    @Path("/eventTypes/{eventTypeId}")
    @Produces(APPLICATION_JSON)
    @RolesAllowed(RbacIdentityProvider.RBAC_READ_NOTIFICATIONS)
    public Uni<List<Endpoint>> getLinkedEndpoints(@Context SecurityContext sec, @PathParam("eventTypeId") UUID eventTypeId, @BeanParam Query query) {
        RhIdPrincipal principal = (RhIdPrincipal) sec.getUserPrincipal();
        return resources.getLinkedEndpoints(principal.getAccount(), eventTypeId, query);
    }

    @PUT
    @Path("/eventTypes/{eventTypeId}/behaviorGroups/{behaviorGroupId}")
    @Produces(TEXT_PLAIN)
    @Operation(summary = "Link a behavior group to an event type.", hidden = true)
    @RolesAllowed(RbacIdentityProvider.RBAC_WRITE_NOTIFICATIONS)
    @APIResponse(responseCode = "200", content = @Content(schema = @Schema(type = SchemaType.STRING)))
    public Uni<Response> linkBehaviorGroupToEventType(@Context SecurityContext sec, @PathParam("eventTypeId") UUID eventTypeId, @PathParam("behaviorGroupId") UUID behaviorGroupId) {
        return getAccountId(sec)
                .onItem().transformToUni(accountId -> behaviorGroupResources.addEventTypeBehavior(accountId, eventTypeId, behaviorGroupId))
                .replaceWith(() -> Response.ok().build());
    }

    @DELETE
    @Path("/eventTypes/{eventTypeId}/behaviorGroups/{behaviorGroupId}")
    @Operation(summary = "Unlink a behavior group from an event type.", hidden = true)
    @RolesAllowed(RbacIdentityProvider.RBAC_WRITE_NOTIFICATIONS)
    @APIResponse(responseCode = "204", description = "Behavior group has been removed from the event type", content = @Content(schema = @Schema(type = SchemaType.STRING)))
    public Uni<Response> unlinkBehaviorGroupFromEventType(@Context SecurityContext sec, @PathParam("eventTypeId") UUID eventTypeId, @PathParam("behaviorGroupId") UUID behaviorGroupId) {
        return getAccountId(sec)
                .onItem().transformToUni(accountId -> behaviorGroupResources.deleteEventTypeBehavior(accountId, eventTypeId, behaviorGroupId))
                .replaceWith(() -> Response.noContent().build());
    }

    @GET
    @Path("/eventTypes/{eventTypeId}/behaviorGroups")
    @Produces(APPLICATION_JSON)
    @Operation(summary = "Retrieve the behavior groups linked to an event type.", hidden = true)
    @RolesAllowed(RbacIdentityProvider.RBAC_READ_NOTIFICATIONS)
    public Uni<List<BehaviorGroup>> getLinkedBehaviorGroups(@Context SecurityContext sec, @PathParam("eventTypeId") UUID eventTypeId, @BeanParam Query query) {
        return getAccountId(sec)
                .onItem().transformToUni(accountId -> behaviorGroupResources.findBehaviorGroupsByEventTypeId(accountId, eventTypeId, query));
    }

    // TODO [BG Phase 2] Delete this method
    @GET
    @Path("/defaults")
    @Produces(APPLICATION_JSON)
    @RolesAllowed(RbacIdentityProvider.RBAC_READ_NOTIFICATIONS)
    @Operation(summary = "Retrieve all integrations of the configured default actions.")
    public Uni<List<Endpoint>> getEndpointsForDefaults(@Context SecurityContext sec) {
        RhIdPrincipal principal = (RhIdPrincipal) sec.getUserPrincipal();
        return resources.getDefaultEndpoints(principal.getAccount());
    }

    // TODO [BG Phase 2] Delete this method
    @PUT
    @Path("/defaults/{endpointId}")
    @Produces(TEXT_PLAIN)
    @Operation(summary = "Add an integration to the list of configured default actions.")
    @APIResponse(responseCode = "200", content = @Content(schema = @Schema(type = SchemaType.STRING)))
    @RolesAllowed(RbacIdentityProvider.RBAC_WRITE_NOTIFICATIONS)
    public Uni<Response> addEndpointToDefaults(@Context SecurityContext sec, @PathParam("endpointId") UUID endpointId) {
        RhIdPrincipal principal = (RhIdPrincipal) sec.getUserPrincipal();
        return resources.addEndpointToDefaults(principal.getAccount(), endpointId)
                .onItem().transform(ignored -> Response.ok().build());
    }

    // TODO [BG Phase 2] Delete this method
    @DELETE
    @Path("/defaults/{endpointId}")
    @Operation(summary = "Remove an integration from the list of configured default actions.")
    @APIResponse(responseCode = "204", description = "Integration has been removed from the default actions", content = @Content(schema = @Schema(type = SchemaType.STRING)))
    @RolesAllowed(RbacIdentityProvider.RBAC_WRITE_NOTIFICATIONS)
    public Uni<Response> deleteEndpointFromDefaults(@Context SecurityContext sec, @PathParam("endpointId") UUID endpointId) {
        RhIdPrincipal principal = (RhIdPrincipal) sec.getUserPrincipal();
        return resources.deleteEndpointFromDefaults(principal.getAccount(), endpointId)
                .onItem().transform(ignored -> Response.noContent().build());
    }

    @GET
    @Path("/facets/applications")
    @Produces(APPLICATION_JSON)
    @Operation(summary = "Return a thin list of configured applications. This can be used to configure a filter in the UI")
    public Uni<List<Facet>> getApplicationsFacets(@Context SecurityContext sec, @QueryParam("bundleName") String bundleName) {
        return apps.getApplications(bundleName)
                .onItem().transform(apps -> apps.stream()
                        .map(a -> new Facet(a.getId().toString(), a.getName(), a.getDisplayName()))
                        .collect(Collectors.toList())
                );
    }

    @GET
    @Path("/facets/bundles")
    @Produces(APPLICATION_JSON)
    @Operation(summary = "Return a thin list of configured bundles. This can be used to configure a filter in the UI")
    public Uni<List<Facet>> getBundleFacets(@Context SecurityContext sec) {
        return bundleResources.getBundles()
                .onItem().transform(bundles -> bundles.stream()
                        .map(b -> new Facet(b.getId().toString(), b.getName(), b.getDisplayName()))
                        .collect(Collectors.toList())
                );
    }

    @DELETE
    @Path("/eventTypes/{eventTypeId}/mute")
    @Produces(APPLICATION_JSON)
    @Operation(summary = "Mute an event type, removing all its link with behavior groups.", hidden = true)
    @RolesAllowed(RbacIdentityProvider.RBAC_WRITE_NOTIFICATIONS)
    public Uni<Boolean> muteEventType(@Context SecurityContext sec, @PathParam("eventTypeId") UUID eventTypeId) {
        return getAccountId(sec)
                .onItem().transformToUni(accountId -> behaviorGroupResources.muteEventType(accountId, eventTypeId));
    }

    @POST
    @Path("/behaviorGroups")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    @Operation(summary = "Create a behavior group.", hidden = true)
    @RolesAllowed(RbacIdentityProvider.RBAC_WRITE_NOTIFICATIONS)
    public Uni<BehaviorGroup> createBehaviorGroup(@Context SecurityContext sec, @NotNull @Valid BehaviorGroup behaviorGroup) {
        return getAccountId(sec)
                .onItem().transformToUni(accountId -> behaviorGroupResources.create(accountId, behaviorGroup));
    }

    @PUT
    @Path("/behaviorGroups/{id}")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    @Operation(summary = "Update a behavior group.", hidden = true)
    @RolesAllowed(RbacIdentityProvider.RBAC_WRITE_NOTIFICATIONS)
    public Uni<Boolean> updateBehaviorGroup(@Context SecurityContext sec, @PathParam("id") UUID id, @NotNull @Valid BehaviorGroup behaviorGroup) {
        return getAccountId(sec)
                .onItem().transformToUni(accountId -> {
                    behaviorGroup.setId(id);
                    return behaviorGroupResources.update(accountId, behaviorGroup);
                });
    }

    @DELETE
    @Path("/behaviorGroups/{id}")
    @Produces(APPLICATION_JSON)
    @Operation(summary = "Delete a behavior group.", hidden = true)
    @RolesAllowed(RbacIdentityProvider.RBAC_WRITE_NOTIFICATIONS)
    public Uni<Boolean> deleteBehaviorGroup(@Context SecurityContext sec, @PathParam("id") UUID behaviorGroupId) {
        return getAccountId(sec)
                .onItem().transformToUni(accountId -> behaviorGroupResources.delete(accountId, behaviorGroupId));
    }

    @PUT
    @Path("/behaviorGroups/{behaviorGroupId}/actions")
    @Consumes(APPLICATION_JSON)
    @Produces(TEXT_PLAIN)
    @Operation(summary = "Update the list of actions of a behavior group.", hidden = true)
    @RolesAllowed(RbacIdentityProvider.RBAC_WRITE_NOTIFICATIONS)
    @APIResponse(responseCode = "200", content = @Content(schema = @Schema(type = SchemaType.STRING)))
    public Uni<Response> updateBehaviorGroupActions(@Context SecurityContext sec, @PathParam("behaviorGroupId") UUID behaviorGroupId, List<UUID> endpointIds) {
        // RESTEasy does not reject an invalid List<UUID> body (even when @Valid is used) so we have to do an additional check here.
        if (endpointIds.contains(null)) {
            return Uni.createFrom().failure(new BadRequestException("The endpoints identifiers list should not contain empty values"));
        }
        return getAccountId(sec)
                .onItem().transformToUni(accountId -> behaviorGroupResources.updateBehaviorGroupActions(accountId, behaviorGroupId, endpointIds))
                .onItem().transform(updated -> {
                    if (updated) {
                        return Response.ok().build();
                    } else {
                        return Response.status(Status.NOT_FOUND).build();
                    }
                });
    }

    @GET
    @Path("/bundles/{bundleId}/behaviorGroups")
    @Produces(APPLICATION_JSON)
    @Operation(summary = "Retrieve the behavior groups of a bundle.", hidden = true)
    @RolesAllowed(RbacIdentityProvider.RBAC_READ_NOTIFICATIONS)
    public Uni<List<BehaviorGroup>> findBehaviorGroupsByBundleId(@Context SecurityContext sec, @PathParam("bundleId") UUID bundleId) {
        return getAccountId(sec)
                .onItem().transformToUni(accountId -> behaviorGroupResources.findByBundleId(accountId, bundleId))
                .onItem().call(behaviorGroups -> endpointResources.loadProperties(
                        behaviorGroups
                                .stream()
                                .map(BehaviorGroup::getActions)
                                .filter(Objects::nonNull)
                                .flatMap(Collection::stream)
                                .map(BehaviorGroupAction::getEndpoint)
                                .collect(Collectors.toList())
                        )
                );
    }
}
