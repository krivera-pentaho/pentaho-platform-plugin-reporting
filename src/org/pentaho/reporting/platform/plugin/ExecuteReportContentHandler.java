package org.pentaho.reporting.platform.plugin;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.platform.api.engine.IParameterProvider;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.api.engine.ISolutionFile;
import org.pentaho.platform.api.repository.ISolutionRepository;
import org.pentaho.platform.engine.core.audit.AuditHelper;
import org.pentaho.platform.engine.core.audit.MessageTypes;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.util.UUIDUtil;
import org.pentaho.platform.util.web.MimeHelper;
import org.pentaho.reporting.engine.classic.core.AttributeNames;
import org.pentaho.reporting.engine.classic.core.MasterReport;
import org.pentaho.reporting.engine.classic.core.modules.output.table.html.HtmlTableModule;
import org.pentaho.reporting.engine.classic.core.util.StagingMode;
import org.pentaho.reporting.libraries.base.util.IOUtils;
import org.pentaho.reporting.platform.plugin.messages.Messages;

public class ExecuteReportContentHandler
{
  private static final String FORCED_BUFFERED_WRITING =
      "org.pentaho.reporting.engine.classic.core.modules.output.table.html.ForceBufferedWriting";
  private static final Log logger = LogFactory.getLog(ExecuteReportContentHandler.class);
  private static final StagingMode DEFAULT = StagingMode.THRU;

  private IPentahoSession userSession;
  private ReportContentGenerator contentGenerator;
  private IParameterProvider pathProvider;

  public ExecuteReportContentHandler(final ReportContentGenerator contentGenerator,
                                     final IParameterProvider pathProvider)
  {
    this.contentGenerator = contentGenerator;
    this.pathProvider = pathProvider;
    this.userSession = contentGenerator.getUserSession();
  }

  public void createReportContent(final OutputStream outputStream, final String reportDefinitionPath, final boolean forceDefaultOutputTarget) throws Exception
  {
    // Check whether we should forward ..
    final HttpServletResponse response;
    final HttpServletRequest request;
    if (pathProvider != null) {
      response = (HttpServletResponse) pathProvider.getParameter("httpresponse"); //$NON-NLS-1$ //$NON-NLS-2$
      request = (HttpServletRequest) pathProvider.getParameter("httprequest"); //$NON-NLS-1$
    } else {
      response = null;
      request = null;
    }
    if (request == null || response == null || isRedirectEnabled() == false)
    {
      doExport(outputStream, reportDefinitionPath, forceDefaultOutputTarget);
      return;
    }

    final String fileContent = pathProvider.getStringParameter("path", null);
    if ("post".equalsIgnoreCase(request.getMethod()) ||
        fileContent != null && fileContent.startsWith("/execute/"))
    {
      doExport(outputStream, reportDefinitionPath, forceDefaultOutputTarget);
    }
    else
    {
      // get the mime type for the report ..
      final SimpleReportingComponent reportComponent = new SimpleReportingComponent();
      reportComponent.setReportDefinitionPath(reportDefinitionPath);
      reportComponent.setPaginateOutput(true);
      reportComponent.setForceDefaultOutputTarget(forceDefaultOutputTarget);
      reportComponent.setDefaultOutputTarget(HtmlTableModule.TABLE_HTML_PAGE_EXPORT_TYPE);
      final Map<String, Object> inputs = contentGenerator.createInputs();
      reportComponent.setInputs(inputs);
      // add all inputs (request parameters) to report component so that we can compute the mime type properly
      final String mimeType = reportComponent.getMimeType();
      final String extension = MimeHelper.getExtension(mimeType);

      final String fileName = IOUtils.getInstance().stripFileExtension(reportDefinitionPath);
      final String requestURI = getUrl(request, fileName + extension);
      response.sendRedirect(requestURI);
    }
  }

  private boolean isRedirectEnabled()
  {
    final String redirect = PentahoSystem.getSystemSetting("report-execute-redirect-to-virtual-file", "true"); //$NON-NLS-1$
    if ("false".equals(redirect))
    {
      return false;
    }
    return true;
  }

  public static String getUrl(final HttpServletRequest req,
                              String reportDefinitionPath)
  {
    final StringBuffer reqUrl = req.getRequestURL();
    reqUrl.append("/execute/");
    reportDefinitionPath = reportDefinitionPath.replace("?", "");
    reportDefinitionPath = reportDefinitionPath.replace("&", "");
    reportDefinitionPath = reportDefinitionPath.replace("*", "");
    reportDefinitionPath = reportDefinitionPath.replace("=", "");
    reportDefinitionPath = reportDefinitionPath.replace("../", "");
    reqUrl.append(reportDefinitionPath);
    final String queryString = req.getQueryString();   // d=789
    if (queryString != null)
    {
      reqUrl.append("?");
      reqUrl.append(queryString);
    }
    return reqUrl.toString();
  }

  private void doExport(final OutputStream outputStream, final String reportDefinitionPath, final boolean forceDefaultOutputTarget) throws Exception
  {
    final long start = System.currentTimeMillis();
    final Map<String, Object> inputs = contentGenerator.createInputs();
    AuditHelper.audit(userSession.getId(), userSession.getName(), reportDefinitionPath,
        contentGenerator.getObjectName(), getClass().getName(), MessageTypes.INSTANCE_START,
        contentGenerator.getInstanceId(), "", 0, contentGenerator); //$NON-NLS-1$

    final Object rawSessionId = inputs.get(ParameterXmlContentHandler.SYS_PARAM_SESSION_ID);
    if ((rawSessionId instanceof String) == false || "".equals(rawSessionId))
    {
      inputs.put(ParameterXmlContentHandler.SYS_PARAM_SESSION_ID, UUIDUtil.getUUIDAsString());
    }

    String result = MessageTypes.INSTANCE_END;
    StagingHandler reportStagingHandler = null;
    try
    {
      // produce rendered report
      final SimpleReportingComponent reportComponent = new SimpleReportingComponent();
      reportComponent.setSession(userSession);
      reportComponent.setReportDefinitionPath(reportDefinitionPath);
      reportComponent.setPaginateOutput(true);
      reportComponent.setForceDefaultOutputTarget(forceDefaultOutputTarget);
      reportComponent.setDefaultOutputTarget(HtmlTableModule.TABLE_HTML_PAGE_EXPORT_TYPE);
      reportComponent.setInputs(inputs);

      final MasterReport report = reportComponent.getReport();
      final StagingMode stagingMode = getStagingMode(inputs, report);
      reportStagingHandler = new StagingHandler(outputStream, stagingMode, this.userSession);

      if (reportStagingHandler.isFullyBuffered())
      {
        // it is safe to disable the buffered writing for the report now that we have a
        // extra buffering in place. 
        report.getReportConfiguration().setConfigProperty(FORCED_BUFFERED_WRITING, "false");
      }

      reportComponent.setOutputStream(reportStagingHandler.getStagingOutputStream());

      // the requested mime type can be null, in that case the report-component will resolve the desired
      // type from the output-target.
      // Hoever, the report-component will inspect the inputs independently from the mimetype here.

      final ISolutionRepository repository = PentahoSystem.get(ISolutionRepository.class, userSession);
      final ISolutionFile file = repository.getSolutionFile(reportDefinitionPath, ISolutionRepository.ACTION_EXECUTE);

      // add all inputs (request parameters) to report component
      final String mimeType = reportComponent.getMimeType();

      // If we haven't set an accepted page, -1 will be the default, which will give us a report
      // with no pages. This default is used so that when we do our parameter interaction with the
      // engine we can spend as little time as possible rendering unused pages, making it no pages.
      // We are going to intentionally reset the accepted page to the first page, 0, at this point,
      // if the accepted page is -1.
      final String outputTarget = reportComponent.getComputedOutputTarget();
      if (HtmlTableModule.TABLE_HTML_PAGE_EXPORT_TYPE.equals(outputTarget) && reportComponent.getAcceptedPage() < 0)
      {
        reportComponent.setAcceptedPage(0);
      }

      if (logger.isDebugEnabled())
      {
        logger.debug(Messages.getInstance().getString("ReportPlugin.logStartGenerateContent", mimeType,//$NON-NLS-1$
            outputTarget, String.valueOf(reportComponent.getAcceptedPage())));
      }

      HttpServletResponse response = null;
      boolean streamToBrowser = false;
      final IParameterProvider pathProviders = contentGenerator.getParameterProviders().get("path");
      if (pathProviders != null)
      {
        final Object httpResponse = pathProviders.getParameter("httpresponse");
        if (httpResponse instanceof HttpServletResponse)
        { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
          response = (HttpServletResponse) httpResponse; //$NON-NLS-1$ //$NON-NLS-2$
          if (reportStagingHandler.getStagingMode() == StagingMode.THRU)
          {
            // Direct back - check output stream...
            final OutputStream respOutputStream = response.getOutputStream();
            if (respOutputStream == outputStream)
            {
              //
              // Massive assumption here -
              //  Assume the container returns the same object on successive calls to response.getOutputStream()
              streamToBrowser = true;
            }
          }
        }
      }

      final String extension = MimeHelper.getExtension(mimeType);
      String filename = file.getFileName();
      if (filename.lastIndexOf(".") != -1)
      { //$NON-NLS-1$
        filename = filename.substring(0, filename.lastIndexOf(".")); //$NON-NLS-1$
      }

      final boolean validates = reportComponent.validate();
      if (!validates)
      {
        sendErrorResponse(response, outputStream, reportStagingHandler);
      }
      else
      {
        if (streamToBrowser)
        {
          // Send headers before we begin execution
          response.setHeader("Content-Disposition", "inline; filename=\"" + filename + extension + "\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
          response.setHeader("Content-Description", file.getFileName()); //$NON-NLS-1$
          response.setHeader("Cache-Control", "private, max-age=0, must-revalidate");
        }
        if (reportComponent.execute())
        {
          if (response != null)
          {
            if (reportStagingHandler.canSendHeaders())
            {
              response.setHeader("Content-Disposition", "inline; filename=\"" + filename + extension + "\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
              response.setHeader("Content-Description", file.getFileName()); //$NON-NLS-1$
              response.setHeader("Cache-Control", "private, max-age=0, must-revalidate");
              response.setContentLength(reportStagingHandler.getWrittenByteCount());
            }
          }
          if (logger.isDebugEnabled())
          {
            logger.debug(Messages.getInstance().getString("ReportPlugin.logEndGenerateContent", String.valueOf(reportStagingHandler.getWrittenByteCount())));//$NON-NLS-1$
          }
          reportStagingHandler.complete(); // will copy bytes to final destination...

        }
        else
        { // failed execution
          sendErrorResponse(response, outputStream, reportStagingHandler);
        }
      }
    }
    catch (Exception ex)
    {
      result = MessageTypes.INSTANCE_FAILED;
      throw ex;
    }
    finally
    {
      if (reportStagingHandler != null)
      {
        reportStagingHandler.close();
      }
      final long end = System.currentTimeMillis();
      AuditHelper.audit(userSession.getId(), userSession.getName(), reportDefinitionPath,
          contentGenerator.getObjectName(), getClass().getName(), result, contentGenerator.getInstanceId(),
          "", ((float) (end - start) / 1000), contentGenerator); //$NON-NLS-1$
    }
  }

  private StagingMode getStagingMode(final Map<String, Object> inputs,
                                     final MasterReport report)
  {
    final Object o = inputs.get("report-staging-mode");
    if (o != null)
    {
      try
      {
        return StagingMode.valueOf(String.valueOf(o));
      }
      catch (IllegalArgumentException ie)
      {
        logger.trace("Staging mode was specified but invalid");
      }
    }

    StagingMode mode = (StagingMode) report.getAttribute
        (AttributeNames.Pentaho.NAMESPACE, AttributeNames.Pentaho.STAGING_MODE);
    if (mode == null)
    {
      logger.trace("Looking at default settings for mode"); //$NON-NLS-1$
      // Unable to use the plugin settings.xml because the
      // classloader for the ReportContentGenerator isn't the plugin classloader
      // IPluginResourceLoader resLoader = PentahoSystem.get(IPluginResourceLoader.class, null);
      // String defaultStagingMode = resLoader.getPluginSetting(ReportContentGenerator.class, "settings/report-staging-mode"); //$NON-NLS-1$
      //
      // So - get default setting from the pentaho.xml instead
      String defaultStagingMode = PentahoSystem.getSystemSetting("report-staging-mode", null); //$NON-NLS-1$
      if (defaultStagingMode == null)
      {
        // workaround for a bug in getPluginSetting that ignores the default passed in
        defaultStagingMode = DEFAULT.toString();//$NON-NLS-1$
        logger.trace("Nothing in settings/staging-mode - defaulting to MEMORY"); //$NON-NLS-1$
      }
      else
      {
        logger.trace("Read " + defaultStagingMode + " from settings/report-staging-mode");            //$NON-NLS-1$//$NON-NLS-2$
      }
      try
      {
        mode = StagingMode.valueOf(defaultStagingMode.toUpperCase());
        logger.trace("Staging mode set from default - " + mode); //$NON-NLS-1$
      }
      catch (IllegalArgumentException badStringInSettings)
      {
        mode = DEFAULT; // default state - handling staging in memory by default.
      }
    }
    return mode;
  }

  private void sendErrorResponse(final HttpServletResponse response,
                                 final OutputStream outputStream,
                                 final StagingHandler reportStagingHandler)
      throws IOException
  {
    if (response != null)
    {
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
    if (logger.isDebugEnabled())
    {
      logger.debug(Messages.getInstance().getString("ReportPlugin.logErrorGenerateContent"));//$NON-NLS-1$
    }
    if (reportStagingHandler.canSendHeaders())
    {
      //
      // Can send headers is another way to check whether the real destination has been
      // pre-polluted with data.
      //
      outputStream.write(Messages.getInstance().getString("ReportPlugin.ReportValidationFailed").getBytes()); //$NON-NLS-1$
      outputStream.flush();
    }
  }

}
