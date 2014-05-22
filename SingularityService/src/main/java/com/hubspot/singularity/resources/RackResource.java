package com.hubspot.singularity.resources;

import com.google.inject.Inject;
import com.hubspot.singularity.SingularityRack;
import com.hubspot.singularity.SingularityService;
import com.hubspot.singularity.data.RackManager;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;

@Path(SingularityService.API_BASE_PATH + "/racks")
@Produces({ MediaType.APPLICATION_JSON })
public class RackResource extends AbstractMachineResource<SingularityRack> {
  
  private final RackManager rackManager;
  
  @Inject
  public RackResource(RackManager rackManager) {
    super(rackManager);
    this.rackManager = rackManager;
  }
  
  @Override
  protected String getObjectTypeString() {
    return "Rack";
  }

  @GET
  @Path("/active")
  public List<SingularityRack> getRacks() {
    return rackManager.getActiveObjects();
  }
  
  @GET
  @Path("/dead")
  public List<SingularityRack> getDead() {
    return rackManager.getDeadObjects();
  }
  
  @GET
  @Path("/decomissioning")
  public List<SingularityRack> getDecomissioning() {
    return rackManager.getDecomissioningObjects();
  }

  @DELETE
  @Path("/rack/{rackId}/dead")
  public void removeDeadRack(@PathParam("rackId") String rackId) {
    super.removeDead(rackId);
  }
  
  @DELETE
  @Path("/rack/{rackId}/decomissioning")
  public void removeDecomissioningRack(@PathParam("rackId") String rackId) {
    super.removeDecomissioning(rackId);
  }
  
  @POST
  @Path("/rack/{rackId}/decomission")
  public void decomissionRack(@PathParam("rackId") String rackId) {
    super.decomission(rackId);
  }
   
}
