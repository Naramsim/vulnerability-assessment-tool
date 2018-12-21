package com.sap.psr.vulas.backend.rest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.persistence.EntityNotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sap.psr.vulas.backend.model.Application;
import com.sap.psr.vulas.backend.model.Space;
import com.sap.psr.vulas.backend.model.Tenant;
import com.sap.psr.vulas.backend.repo.ApplicationRepository;
import com.sap.psr.vulas.backend.repo.SpaceRepository;
import com.sap.psr.vulas.backend.repo.TenantRepository;
import com.sap.psr.vulas.backend.util.TokenUtil;
import com.sap.psr.vulas.shared.util.Constants;
import com.sap.psr.vulas.shared.util.StopWatch;
import com.sap.psr.vulas.shared.util.StringList;
import com.sap.psr.vulas.shared.util.VulasConfiguration;
import com.sap.psr.vulas.shared.util.StringList.CaseSensitivity;
import com.sap.psr.vulas.shared.util.StringList.ComparisonMode;

import springfox.documentation.annotations.ApiIgnore;

@RestController
@CrossOrigin("*")
@RequestMapping("/spaces")
public class SpaceController {

	private static Logger log = LoggerFactory.getLogger(SpaceController.class);
	
	private static String SPACE_DO_NOT_DELETE = "vulas.backend.space.doNotDelete";
	
	private static String SPACE_DO_NOT_CLEAN = "vulas.backend.space.doNotClean";
	
	private static String SPACE_DO_NOT_MODIFY = "vulas.backend.space.doNotModify";

	private SpaceRepository spaceRepository;

	private TenantRepository tenantRepository;
	
	private ApplicationRepository appRepository;

	@Autowired
	SpaceController(TenantRepository tenantRepository, SpaceRepository spaceRepository, ApplicationRepository appRepository) {
		this.tenantRepository = tenantRepository;
		this.spaceRepository = spaceRepository;
		this.appRepository = appRepository;

		//(SP, 27-10-2017) It is not mandatory to have default tenant & spaces. This is only required for 
		// the existing internal VULAS system to be backward compatible with vulas 2.x
	}

	/**
	 * Returns all {@link Space}s of the given {@link Tenant}.
	 * @param token
	 * @return 404 {@link HttpStatus#NOT_FOUND} if space with given token does not exist, 200 {@link HttpStatus#OK} if the space is found
	 */
	@RequestMapping(value = "", method = RequestMethod.GET, produces = {"application/json;charset=UTF-8"})
	public ResponseEntity<Collection<Space>> getAllSpaces(
			@ApiIgnore @RequestHeader(value=Constants.HTTP_TENANT_HEADER, required=true) String tenant) {
		try {
			// Check whether tenant exists
			final Tenant t = TenantRepository.FILTER.findOne(this.tenantRepository.findBySecondaryKey(tenant));
			// Get all its spaces
			final Collection<Space> all_tenant_spaces = this.spaceRepository.findAllTenantSpaces(tenant, true);
			return new ResponseEntity<Collection<Space>>(all_tenant_spaces, HttpStatus.OK);
		}
		catch(EntityNotFoundException enfe) {
			return new ResponseEntity<Collection<Space>>(HttpStatus.NOT_FOUND);
		}
	}
	
	/**
	 * Returns the default {@link Space} of the given {@link Tenant}.
	 * @param token
	 * @return 404 {@link HttpStatus#NOT_FOUND} if no default space exists, 200 {@link HttpStatus#OK} if the space is found
	 */
	@RequestMapping(value = "default", method = RequestMethod.GET, produces = {"application/json;charset=UTF-8"})
	public ResponseEntity<Space> getDefaultSpace(
			@ApiIgnore @RequestHeader(value=Constants.HTTP_TENANT_HEADER, required=true) String tenant) {
		try {
			// Check whether tenant exists
			final Tenant t = TenantRepository.FILTER.findOne(this.tenantRepository.findBySecondaryKey(tenant));
			// Get default space
			final Space def = this.spaceRepository.findDefault(tenant);
			if(def==null)
				throw new EntityNotFoundException("No default space can be found for tenant [" + tenant + "]");
			else
				return new ResponseEntity<Space>(def, HttpStatus.OK);
		}
		catch(EntityNotFoundException enfe) {
			return new ResponseEntity<Space>(HttpStatus.NOT_FOUND);
		}
	}
	
	/**
	 * Checks whether a {@link Space} with the given token exists for the given {@link Tenant}.
	 * @param token
	 * @return 404 {@link HttpStatus#NOT_FOUND} if space with given token does not exist, 200 {@link HttpStatus#OK} if the space is found
	 */
	@RequestMapping(value = "/{token:.+}", method = RequestMethod.OPTIONS)
	public ResponseEntity<Space> isSpaceExisting(
			@PathVariable String token,
			@ApiIgnore @RequestHeader(value=Constants.HTTP_TENANT_HEADER, required=true) String tenant) {
		try {
			SpaceRepository.FILTER.findOne(this.spaceRepository.findBySecondaryKey(tenant, token));
			return new ResponseEntity<Space>(HttpStatus.OK);
		}
		catch(EntityNotFoundException enfe) {
			return new ResponseEntity<Space>(HttpStatus.NOT_FOUND);
		}
	}
	
	/**
	 * Returns a {@link Space} with the given token and the given {@link Tenant}.
	 * @param token
	 * @return 404 {@link HttpStatus#NOT_FOUND} if space with given token does not exist, 200 {@link HttpStatus#OK} if the space is found
	 */
	@RequestMapping(value = "/{token:.+}", method = RequestMethod.GET)
	public ResponseEntity<Space> getSpace(
			@PathVariable String token,
			@ApiIgnore @RequestHeader(value=Constants.HTTP_TENANT_HEADER, required=true) String tenant) {
		try {
			final Space s = SpaceRepository.FILTER.findOne(this.spaceRepository.findBySecondaryKey(tenant, token));
			return new ResponseEntity<Space>(s, HttpStatus.OK);
		}
		catch(EntityNotFoundException enfe) {
			return new ResponseEntity<Space>(HttpStatus.NOT_FOUND);
		}
	}

	/**
	 * Creates a new {@link Space} with a new, random token in the database and returns it to the client.
	 */
	@RequestMapping(value = "", method = RequestMethod.POST, consumes = {"application/json;charset=UTF-8"}, produces = {"application/json;charset=UTF-8"})
	public ResponseEntity<Space> createSpace(
			@RequestBody Space space,
			@ApiIgnore @RequestHeader(value=Constants.HTTP_TENANT_HEADER, required=true) String tenant) {
		final StopWatch sw = new StopWatch("Create space [" + (space==null?null:space.getSpaceName()) + "] "
				+ "for tenant token [" + tenant +"] ").start();
		try {
			// Check arguments
			if(space==null) {
				log.error("No space submitted");
				return new ResponseEntity<Space>(HttpStatus.BAD_REQUEST);
			}
			else if(!space.hasNameAndDescription()) {
				log.error("Space creation requires name and description, adjust the configuration accordingly");
				return new ResponseEntity<Space>(HttpStatus.BAD_REQUEST);
			}

			// Get the tenant
			Tenant t = null;
			try {
				t = TenantRepository.FILTER.findOne(this.tenantRepository.findBySecondaryKey(tenant));
			}
			catch(EntityNotFoundException enfe) {
				log.error("Tenant [" + tenant + "] not found");
				return new ResponseEntity<Space>(HttpStatus.NOT_FOUND);
			}
			//check that only 1 default space per tenant is created
			if(space.isDefault()&& this.spaceRepository.findDefault(t.getTenantToken())!=null){
				log.error("A default space for the given tenant already exists, adjust the configuration accordingly");
				return new ResponseEntity<Space>(HttpStatus.BAD_REQUEST);
			}

			// Always create token, whatever has been submitted
			space.setTenant(t);
			space.setSpaceToken(TokenUtil.generateToken());
			this.spaceRepository.customSave(space);

			sw.stop();
			return new ResponseEntity<Space>(space, HttpStatus.CREATED);
		}
		catch(Exception enfe) {
			sw.stop(enfe);
			return new ResponseEntity<Space>(HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Modifies an existing {@link Space} and returns it to the client.
	 */
	@RequestMapping(value = "/{token:.+}", method = RequestMethod.PUT, consumes = {"application/json;charset=UTF-8"}, produces = {"application/json;charset=UTF-8"})
	public ResponseEntity<Space> modifySpace(
			@PathVariable String token,
			@RequestBody Space new_space,
			@ApiIgnore @RequestHeader(value=Constants.HTTP_TENANT_HEADER, required=true) String tenant) {
		try {
			// Check arguments
			if(new_space==null) {
				log.error("No space submitted");
				return new ResponseEntity<Space>(HttpStatus.BAD_REQUEST);
			}
			else if(!token.equals(new_space.getSpaceToken())) {
				log.error("Token in path variable and request body do not match");
				return new ResponseEntity<Space>(HttpStatus.BAD_REQUEST);
			}
			else if(!new_space.hasNameAndDescription()) {
				log.error("Space modification requires name and description, adjust the configuration accordingly");
				return new ResponseEntity<Space>(HttpStatus.BAD_REQUEST);
			}

			// Get the tenant
			Tenant t = null;
			try {
				t = TenantRepository.FILTER.findOne(this.tenantRepository.findBySecondaryKey(tenant));
			}
			catch(EntityNotFoundException enfe) {
				log.error("Tenant [" + tenant + "] not found");
				return new ResponseEntity<Space>(HttpStatus.NOT_FOUND);
			}
			
			// Prevent the modification of spaces with configured tokens
			final StringList do_not_modify = new StringList(VulasConfiguration.getGlobal().getStringArray(SPACE_DO_NOT_MODIFY, null));
			if(do_not_modify.contains(token, ComparisonMode.EQUALS, CaseSensitivity.CASE_INSENSITIVE)) {
				log.error("The following spaces cannot be modified according to configuration setting [" + SPACE_DO_NOT_MODIFY + "]: " + do_not_modify);
				return new ResponseEntity<Space>(HttpStatus.BAD_REQUEST);
			}

			// Check that only 1 default space per tenant is created
			if(new_space.isDefault()){
				Space default_space = this.spaceRepository.findDefault(t.getTenantToken());
				if(default_space!=null && !default_space.getSpaceToken().equals(token)){
					log.error("A default space for the given tenant already exists, adjust the configuration accordingly");
					return new ResponseEntity<Space>(HttpStatus.BAD_REQUEST);
				}
			}			

			// Load existing space, update members and save
			try {
				new_space.setTenant(t);
				return new ResponseEntity<Space>(this.spaceRepository.customSave(new_space), HttpStatus.OK);
			}
			catch(EntityNotFoundException enfe) {
				return new ResponseEntity<Space>(HttpStatus.NOT_FOUND);
			}
		}
		catch(Exception enfe) {
			return new ResponseEntity<Space>(HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
	
	/**
	 * Cleans a given {@link Space}, i.e., removes all its applications.
	 */
	@RequestMapping(value = "/{token:.+}", method = RequestMethod.POST)
	public ResponseEntity<Space> cleanSpace(
			@PathVariable String token,
			@RequestParam(value="clean", required=true) Boolean clean,
			@ApiIgnore @RequestHeader(value=Constants.HTTP_TENANT_HEADER, required=true) String tenant) {
		try {

			// Get the tenant
			Tenant t = null;
			try {
				t = TenantRepository.FILTER.findOne(this.tenantRepository.findBySecondaryKey(tenant));
			}
			catch(EntityNotFoundException enfe) {
				log.error("Tenant [" + tenant + "] not found");
				return new ResponseEntity<Space>(HttpStatus.NOT_FOUND);
			}

			// Load existing space, and delete
			try {
				final Space space = SpaceRepository.FILTER.findOne(this.spaceRepository.findBySecondaryKey(t, token));
				
				// Prevent cleaning of default space in default tenant
				if(space.isDefault() && t.isDefault()) {
					log.error("The default space of the default tenant cannot be cleaned as long as we want to support Vulas 2.x");
					return new ResponseEntity<Space>(HttpStatus.BAD_REQUEST);
				}
				
				// Prevent the cleaning of spaces with configured tokens
				final StringList do_not_clean = new StringList(VulasConfiguration.getGlobal().getStringArray(SPACE_DO_NOT_CLEAN, null));
				if(do_not_clean.contains(token, ComparisonMode.EQUALS, CaseSensitivity.CASE_INSENSITIVE)) {
					log.error("The following spaces cannot be cleaned according to configuration setting [" + SPACE_DO_NOT_CLEAN + "]: " + do_not_clean);
					return new ResponseEntity<Space>(HttpStatus.BAD_REQUEST);
				}
				
				if(clean) {
					// Find all applications in the space
					final List<Application> apps = this.appRepository.findAllApps(token,0);
	
					// The deleted apps
					final List<Application> deleted_apps = new ArrayList<Application>();
					
					// Apps are found
					if(apps!=null && !apps.isEmpty()){
						for(Application app: apps) {
							deleted_apps.add(app);
							try {
								this.appRepository.deleteAnalysisResults(app, true);
								this.appRepository.delete(app);
							} catch (Exception e) {
								log.error("Error while deleting app " + app + ": " + e.getMessage(), e);
							}
						}
					}
					if(deleted_apps.size()>0) {
						log.info("Deleted the following [" + deleted_apps.size() + "] apps:");
						for(Application app: deleted_apps)
							log.info("    " + app);
					}
				}
				else {
					log.warn("Parameter [clean] is set to [false], space " + space + " will not be cleaned");
				}

				return new ResponseEntity<Space>(HttpStatus.OK);
			}
			catch(EntityNotFoundException enfe) {
				return new ResponseEntity<Space>(HttpStatus.NOT_FOUND);
			}
		}
		catch(Exception enfe) {
			return new ResponseEntity<Space>(HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Deletes a given {@link Space}.
	 */
	@RequestMapping(value = "/{token:.+}", method = RequestMethod.DELETE)
	public ResponseEntity<Space> deleteSpace(
			@PathVariable String token,
			@ApiIgnore @RequestHeader(value=Constants.HTTP_TENANT_HEADER, required=true) String tenant) {
		try {

			// Get the tenant
			Tenant t = null;
			try {
				t = TenantRepository.FILTER.findOne(this.tenantRepository.findBySecondaryKey(tenant));
			}
			catch(EntityNotFoundException enfe) {
				log.error("Tenant [" + tenant + "] not found");
				return new ResponseEntity<Space>(HttpStatus.NOT_FOUND);
			}

			// Load existing space, and delete
			try {
				final Space space = SpaceRepository.FILTER.findOne(this.spaceRepository.findBySecondaryKey(t, token));
				
				// Prevent deletion of default space in default tenant. This is needed to make sure that scans w/o space token work
				if(space.isDefault() && t.isDefault()){
					log.error("The default space of the default tenant cannot be deleted as long as we want to support Vulas 2.x");
					return new ResponseEntity<Space>(HttpStatus.BAD_REQUEST);
				}
				
				// Prevent the deletion of spaces with configured tokens
				final StringList do_not_delete = new StringList(VulasConfiguration.getGlobal().getStringArray(SPACE_DO_NOT_DELETE, null));
				if(do_not_delete.contains(token, ComparisonMode.EQUALS, CaseSensitivity.CASE_INSENSITIVE)) {
					log.error("The following spaces cannot be deleted according to configuration setting [" + SPACE_DO_NOT_DELETE + "]: " + do_not_delete);
					return new ResponseEntity<Space>(HttpStatus.BAD_REQUEST);
				}
				
				// Find all applications in the space
				final List<Application> apps = this.appRepository.findAllApps(token,0);

				// The deleted apps
				final List<Application> deleted_apps = new ArrayList<Application>();
				
				// Apps are found
				if(apps!=null && !apps.isEmpty()){
					for(Application app: apps) {
						deleted_apps.add(app);
						try {
							this.appRepository.deleteAnalysisResults(app, true);
							this.appRepository.delete(app);
						} catch (Exception e) {
							log.error("Error while deleting app " + app + ": " + e.getMessage(), e);
						}
					}
				}
				if(deleted_apps.size()>0) {
					log.info("Deleted the following [" + deleted_apps.size() + "] apps:");
					for(Application app: deleted_apps)
						log.info("    " + app);
				}

				log.info("Going to delete space: "+space.getSpaceName() + " with token [" + space.getSpaceToken()+ "], id [" +space.getId()+"]");
				//delete the space
				try {
					this.spaceRepository.delete(space);
				} catch (Exception e) {
					log.error("Error while deleting space " + space.getSpaceName() + ": " + e.getMessage(), e);
				}
				

				return new ResponseEntity<Space>(HttpStatus.OK);
			}
			catch(EntityNotFoundException enfe) {
				return new ResponseEntity<Space>(HttpStatus.NOT_FOUND);
			}
		}
		catch(Exception enfe) {
			return new ResponseEntity<Space>(HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
}