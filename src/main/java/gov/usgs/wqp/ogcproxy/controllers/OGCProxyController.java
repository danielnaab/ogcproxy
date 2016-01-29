package gov.usgs.wqp.ogcproxy.controllers;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.ModelAndView;

import gov.usgs.wqp.ogcproxy.model.ogc.parameters.WFSParameters;
import gov.usgs.wqp.ogcproxy.model.ogc.services.OGCServices;
import gov.usgs.wqp.ogcproxy.model.parser.xml.ogc.OgcWfsParser;
import gov.usgs.wqp.ogcproxy.model.parser.xml.ogc.RequestWrapper;
import gov.usgs.wqp.ogcproxy.services.ProxyService;
import gov.usgs.wqp.ogcproxy.services.RESTService;
import gov.usgs.wqp.ogcproxy.utils.ApplicationVersion;
import gov.usgs.wqp.ogcproxy.utils.ProxyUtil;

@Controller
public class OGCProxyController {
	private static final Logger LOG = LoggerFactory.getLogger(OGCProxyController.class);
	
	/*
	 * Beans		===========================================================
	 * ========================================================================
	 */
	@Autowired
	private ProxyService proxyService;
	
	@Autowired
	private RESTService restService;

	/* ====================================================================== */

	/*
	 * Actions		===========================================================
	 * ========================================================================
	 */
	@RequestMapping(value="/", method=RequestMethod.GET)
    public ModelAndView entry() {
		LOG.debug("OGCProxyController.entry() called");
		
		ModelAndView mv = new ModelAndView("index.jsp");
		mv.addObject("version", ApplicationVersion.getVersion());

		DeferredResult<ModelAndView> finalResult = new DeferredResult<ModelAndView>();
		finalResult.setResult(mv);
		return mv;
    }
	
	//http://www.waterqualitydata.us/qw_portal_map/ows?service=WPS&version=1.0.0&request=Execute&identifier=gs:SingleWpsStatus
	
	@RequestMapping(value="**/wms", method={RequestMethod.GET})
    public DeferredResult<String>  wmsProxy(HttpServletRequest request, HttpServletResponse response, @RequestParam Map<String,String> requestParams) {
		LOG.debug("OGCProxyController.wmsProxy() INFO - Performing request.");
		return handleActualServiceCallGet(request, response, requestParams, OGCServices.WMS);
	}
	
	@RequestMapping(value="**/wfs", method=RequestMethod.GET)
    public DeferredResult<String> wfsProxyGet(HttpServletRequest request, HttpServletResponse response, @RequestParam Map<String,String> requestParams) {
		LOG.debug("OGCProxyController.wfsProxy() INFO - Performing request.");
		return handleActualServiceCallGet(request, response, requestParams, OGCServices.WFS);
	}

	/**
	 * This is here because the URL is a default request service but could receive a request of either WFS/WMS service
	 */
	public DeferredResult<String> handleActualServiceCallGet(HttpServletRequest request,
			HttpServletResponse response, Map<String, String> requestParams, OGCServices ogcService) {
		
		ogcService = ProxyUtil.getRequestedService(ogcService, requestParams);
		
		if (ogcService == OGCServices.WMS) {
			LOG.debug("OGCProxyController.handleActualServiceCallGet() INFO - Performing WMS request.");
			return proxyService.performGetRequestWms(request, response, requestParams);
		} else {
			LOG.debug("OGCProxyController.handleActualServiceCallGet() INFO - Performing WFS request.");
			return proxyService.performGetRequestWfs(request, response, requestParams);
		}
	}

		
	
	/**
	 * This is here because the URL is a default request service but could receive a request of either WFS/WMS service
	 */
	public void handleActualServiceCallPost(HttpServletRequest request,
			HttpServletResponse response, OGCServices ogcService) {
		
		OgcWfsParser ogcParser =  new OgcWfsParser(request);
		Map<String, String> requestParams = ogcParser.requestParamsPayloadToMap();
		request = new RequestWrapper(request, ogcParser.getBodyMinusSearchParams());
		
		ogcService = ProxyUtil.getRequestedService(ogcService, requestParams);

		String primaryLayerName   = WFSParameters.typeName.toString();
		String secondaryLayerName = WFSParameters.typeNames.toString();

		proxyService.performRequest(request, response, requestParams, ogcService, primaryLayerName, secondaryLayerName);
	}

	
	// NEW POST OGC XML WMS/WFS - it will check the payload for the actual service
	@Async
	@RequestMapping(value="**/wms", method=RequestMethod.POST)
    public void wmsProxyPost(HttpServletRequest request, HttpServletResponse response) {
		LOG.debug("OGCProxyController.wmsProxyPost() INFO - Performing request.");
		handleActualServiceCallPost(request, response, OGCServices.WMS);
	}
	// NEW POST OGC XML WMS/WFS - it will check the payload for the actual service
	// TODO if this is not fixed then this could just chain to the method above
	@Async
	@RequestMapping(value="**/wfs", method=RequestMethod.POST)
    public void wfsProxyPost(HttpServletRequest request, HttpServletResponse response) {
		LOG.debug("OGCProxyController.wfsProxyPost() INFO - Performing request.");
		handleActualServiceCallPost(request, response, OGCServices.WFS);
	}
	
	
	@RequestMapping(value="/rest/cachestatus/{site}", method=RequestMethod.GET)
    public DeferredResult<ModelAndView> restCacheStatus(@PathVariable String site) {
		DeferredResult<ModelAndView> finalResult = new DeferredResult<ModelAndView>();
		
		restService.checkCacheStatus(site, finalResult);
		
		return finalResult;
	}
	
	@RequestMapping(value="/rest/clearcache/{site}", method=RequestMethod.GET)
    public DeferredResult<ModelAndView> restClearCache(@PathVariable String site) {
		DeferredResult<ModelAndView> finalResult = new DeferredResult<ModelAndView>();
		
		restService.clearCacheBySite(site, finalResult);
		
		return finalResult;
	}

}
