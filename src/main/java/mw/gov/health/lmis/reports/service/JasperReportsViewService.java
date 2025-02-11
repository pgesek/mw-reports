package mw.gov.health.lmis.reports.service;

import static java.io.File.createTempFile;

import static mw.gov.health.lmis.reports.i18n.JasperMessageKeys.ERROR_GENERATE_REPORT_FAILED;
import static mw.gov.health.lmis.reports.i18n.JasperMessageKeys.ERROR_JASPER_FILE_CREATION;
import static mw.gov.health.lmis.reports.i18n.MessageKeys.ERROR_IO;
import static mw.gov.health.lmis.reports.i18n.MessageKeys.ERROR_JASPER_FILE_FORMAT;
import static mw.gov.health.lmis.reports.i18n.ReportingMessageKeys.ERROR_REPORTING_CLASS_NOT_FOUND;
import static mw.gov.health.lmis.reports.i18n.ReportingMessageKeys.ERROR_REPORTING_IO;
import static mw.gov.health.lmis.reports.web.ReportTypes.ORDER_REPORT;
import static org.apache.commons.io.FileUtils.writeByteArrayToFile;

import mw.gov.health.lmis.reports.dto.RequisitionReportDto;
import mw.gov.health.lmis.reports.dto.external.OrderDto;
import mw.gov.health.lmis.reports.dto.external.OrderLineItemDto;
import mw.gov.health.lmis.reports.dto.external.ProcessingPeriodDto;
import mw.gov.health.lmis.reports.dto.external.RequisitionDto;
import mw.gov.health.lmis.reports.dto.external.RequisitionTemplateColumnDto;
import mw.gov.health.lmis.reports.dto.external.RequisitionTemplateDto;
import mw.gov.health.lmis.reports.dto.external.StockCardDto;
import mw.gov.health.lmis.reports.dto.external.StockCardSummaryDto;
import mw.gov.health.lmis.reports.service.fulfillment.OrderService;
import mw.gov.health.lmis.reports.service.referencedata.BaseReferenceDataService;
import mw.gov.health.lmis.reports.service.referencedata.LotReferenceDataService;
import mw.gov.health.lmis.reports.service.referencedata.OrderableReferenceDataService;
import mw.gov.health.lmis.reports.service.referencedata.PeriodReferenceDataService;
import mw.gov.health.lmis.reports.service.referencedata.StockCardReferenceDataService;
import mw.gov.health.lmis.reports.service.referencedata.StockCardSummariesReferenceDataService;
import mw.gov.health.lmis.reports.service.referencedata.UserReferenceDataService;
import mw.gov.health.lmis.reports.web.RequisitionReportDtoBuilder;
import mw.gov.health.lmis.utils.ReportUtils;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.JRBand;
import net.sf.jasperreports.engine.JRException;

import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.xml.JRXmlLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.springframework.web.servlet.ModelAndView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.sql.Connection;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Date;

import java.text.SimpleDateFormat;  

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.sql.DataSource;

import mw.gov.health.lmis.reports.domain.JasperTemplate;
import mw.gov.health.lmis.reports.exception.JasperReportViewException;
import org.springframework.web.servlet.view.jasperreports.AbstractJasperReportsView;
import org.springframework.web.servlet.view.jasperreports.JasperReportsCsvView;
import org.springframework.web.servlet.view.jasperreports.JasperReportsMultiFormatView;
import org.springframework.web.servlet.view.jasperreports.JasperReportsPdfView;
import org.springframework.web.servlet.view.jasperreports.JasperReportsXlsView;
import org.springframework.web.servlet.view.jasperreports.JasperReportsXlsxView;

@SuppressWarnings("PMD.TooManyMethods")
@Service
public class JasperReportsViewService {

  static final String CARD_SUMMARY_REPORT_URL = "/jasperTemplates/stockCardSummary.jrxml";
  private static final String REQUISITION_REPORT_DIR = "/jasperTemplates/requisition.jrxml";
  private static final String REQUISITION_LINE_REPORT_DIR =
          "/jasperTemplates/requisitionLines.jrxml";
  private static final String DATASOURCE = "datasource";
  static final String PI_LINES_REPORT_URL = "/jasperTemplates/physicalinventoryLines.jrxml";

  @Autowired
  private ApplicationContext appContext;

  @Autowired
  private DataSource replicationDataSource;

  @Autowired
  private PeriodReferenceDataService periodReferenceDataService;

  @Autowired
  private OrderService orderService;

  @Autowired
  private UserReferenceDataService userReferenceDataService;

  @Autowired
  private RequisitionReportDtoBuilder requisitionReportDtoBuilder;

  @Autowired
  private StockCardSummariesReferenceDataService stockCardSummariesDataService;

  @Autowired
  private StockCardReferenceDataService stockCardReferenceDataService;

  @Autowired
  private OrderableReferenceDataService orderableReferenceDataService;

  @Autowired
  private LotReferenceDataService lotReferenceDataService;

  @Value("${dateTimeFormat}")
  private String dateTimeFormat;

  @Value("${dateFormat}")
  private String dateFormat;

  @Value("${groupingSeparator}")
  private String groupingSeparator;

  @Value("${groupingSize}")
  private String groupingSize;

  /**
   * Create Jasper Report View.
   * Create Jasper Report (".jasper" file) from bytes from Template entity.
   * Set 'Jasper' exporter parameters, JDBC data source, web application context, url to file.
   *
   * @param jasperTemplate template that will be used to create a view
   * @param request  it is used to take web application context
   * @return created jasper view.
   * @throws JasperReportViewException if there will be any problem with creating the view.
   */
  public JasperReportsMultiFormatView getJasperReportsView(
      JasperTemplate jasperTemplate, HttpServletRequest request) throws JasperReportViewException {
    JasperReportsMultiFormatView jasperView = new JasperReportsMultiFormatView();
    setFormatMappings(jasperView);
    jasperView.setUrl(getReportUrlForReportData(jasperTemplate));
    jasperView.setJdbcDataSource(replicationDataSource);

    if (getApplicationContext(request) != null) {
      jasperView.setApplicationContext(getApplicationContext(request));
    }
    return jasperView;
  }

  /**
   * Create Jasper Report View.
   * Create Jasper Report (".jasper" file) from bytes from Template entity.
   * Set 'Jasper' exporter parameters, JDBC data source, web application context, url to file.
   *
   * @param jasperTemplate template that will be used to create a view
   * @return created jasper view.
   * @throws JasperReportViewException if there will be any problem with creating the view.
   */
  public JasperReportsMultiFormatView getJasperReportsView(JasperTemplate jasperTemplate)
      throws JasperReportViewException {
    JasperReportsMultiFormatView jasperView = new JasperReportsMultiFormatView();
    setFormatMappings(jasperView);
    jasperView.setJdbcDataSource(replicationDataSource);
    jasperView.setUrl(getReportUrlForReportData(jasperTemplate));
    jasperView.setApplicationContext(appContext);
    return jasperView;
  }

  /**
   * Create Jasper Report View.
   * Create Jasper Report (".jasper" file) from bytes from Template entity.
   * Set 'Jasper' exporter parameters, JDBC data source, web application context, url to file.
   *
   * @param templateUrl template url that will be used to create a view
   * @return created jasper view.
   * @throws JasperReportViewException if there will be any problem with creating the view.
   */
  public JasperReportsMultiFormatView getJasperReportsView(String templateUrl)
      throws JasperReportViewException {
    JasperReportsMultiFormatView view = new JasperReportsMultiFormatView();
    view.setUrl(compileReportAndGetUrl(templateUrl));
    view.setApplicationContext(appContext);
    view.setJdbcDataSource(replicationDataSource);
    view.setApplicationContext(appContext);
    return view;
  }

  /**
   * Get application context from servlet.
   */
  public WebApplicationContext getApplicationContext(HttpServletRequest servletRequest) {
    ServletContext servletContext = servletRequest.getSession().getServletContext();
    return WebApplicationContextUtils.getWebApplicationContext(servletContext);
  }

  /**
   * Create ".jasper" file with byte array from Template.
   *
   * @return Url to ".jasper" file.
   */
  private String getReportUrlForReportData(JasperTemplate jasperTemplate)
      throws JasperReportViewException {
    File tmpFile;

    try {
      tmpFile = createTempFile(jasperTemplate.getName() + "_temp", ".jasper");
    } catch (IOException exp) {
      throw new JasperReportViewException(
          exp, ERROR_JASPER_FILE_CREATION
      );
    }

    try (ObjectInputStream inputStream =
             new ObjectInputStream(new ByteArrayInputStream(jasperTemplate.getData()))) {
      JasperReport jasperReport = (JasperReport) inputStream.readObject();

      try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
           ObjectOutputStream out = new ObjectOutputStream(bos)) {

        out.writeObject(jasperReport);
        writeByteArrayToFile(tmpFile, bos.toByteArray());

        return tmpFile.toURI().toURL().toString();
      }
    } catch (IOException exp) {
      throw new JasperReportViewException(exp, ERROR_REPORTING_IO, exp.getMessage());
    } catch (ClassNotFoundException exp) {
      throw new JasperReportViewException(
          exp, ERROR_REPORTING_CLASS_NOT_FOUND, JasperReport.class.getName());
    }
  }

  /**
   * Get customized Jasper Report View for Order Report.
   *
   * @param jasperView generic jasper report view
   * @param parameters template parameters populated with values from the request
   * @return customized jasper view.
   */
  public ModelAndView getOrderJasperReportView(JasperReportsMultiFormatView jasperView,
                                               Map<String, Object> parameters) {
    OrderDto order = orderService.findOne(
            UUID.fromString(parameters.get("order").toString())
    );
    order.getStatusChanges().forEach(
        statusChange -> statusChange.setAuthor(
            getIfPresent(userReferenceDataService, statusChange.getAuthorId()))
    );
    List<OrderLineItemDto> items = order.getOrderLineItems();
    items.sort(Comparator.comparing(c -> c.getOrderable().getProductCode()));

    parameters.put(DATASOURCE, new JRBeanCollectionDataSource(items));
    parameters.put("order", order);
    parameters.put("orderingPeriod", order.getEmergency()
        ? order.getProcessingPeriod() : findNextPeriod(order.getProcessingPeriod(), null));

    return new ModelAndView(jasperView, parameters);
  }

  /**
   * Create custom Jasper Report View for printing a requisition.
   *
   * @param requisition requisition to render report for.
   * @param request  it is used to take web application context.
   * @return created jasper view.
   * @throws JasperReportViewException if there will be any problem with creating the view.
   */
  public ModelAndView getRequisitionJasperReportView(
          RequisitionDto requisition, HttpServletRequest request) throws JasperReportViewException {
    RequisitionReportDto reportDto = requisitionReportDtoBuilder.build(requisition);
    RequisitionTemplateDto template = requisition.getTemplate();

    Map<String, Object> params = ReportUtils.createParametersMap();
    params.put("subreport", createCustomizedRequisitionLineSubreport(template));
    params.put(DATASOURCE, Collections.singletonList(reportDto));
    params.put("template", template);

    JasperReportsMultiFormatView jasperView = new JasperReportsMultiFormatView();
    setCustomizedJasperTemplateForRequisitionReport(jasperView);

    if (getApplicationContext(request) != null) {
      jasperView.setApplicationContext(getApplicationContext(request));
    }
    return new ModelAndView(jasperView, params);
  }

  /**
   * Get report's filename.
   *
   * @param template jasper template
   * @param params template parameters populated with values from the request
   * @return filename
   */
  public String getFilename(JasperTemplate template, Map<String, Object> params) {
    String templateType = template.getType();
    // start the filename with report's name
    StringBuilder fileName = new StringBuilder(template.getName());
    // add all the params that report takes to the value list
    List<Object> values = new ArrayList<>();
    // if it's Order report, add filename parts manually
    if (ORDER_REPORT.equals(templateType)) {
      OrderDto order = orderService.findOne(
          UUID.fromString(params.get("order").toString())
      );
      ProcessingPeriodDto period = order.getEmergency() ? order.getProcessingPeriod() :
          findNextPeriod(order.getProcessingPeriod(), null);
      values = Arrays.asList(
          order.getProgram().getName(),
          (period != null) ? period.getName() : "",
          order.getFacility().getName()
      );
    } else {
      //add date of generating report to the filename if it's not the order report
      Date date = new Date();
      SimpleDateFormat formatter = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
      values.add(formatter.format(date));
    }
    // add all the parts to the filename and separate them by "_"
    for (Object value : values) {
      fileName
          .append('_')
          .append(value.toString());
    }
    // format file name
    return formatFileName(fileName.toString());
  }

  /**
   * format file name.
   *
   * @param fileName file name which will be formatted
   * @return formatted file name
   */
  public String formatFileName(String fileName) {
    // replace whitespaces with "_", make the filename lowercase
    return fileName
        .replaceAll("\\s+", "_")
        .toLowerCase(Locale.ENGLISH);
  }

  /**
   * Creates PI line sub-report.
   * */
  public JasperDesign createCustomizedPhysicalInventoryLineSubreport()
      throws JasperReportViewException {
    try (InputStream inputStream = getClass().getResourceAsStream(PI_LINES_REPORT_URL)) {
      return JRXmlLoader.load(inputStream);
    } catch (IOException ex) {
      throw new JasperReportViewException(ex, ERROR_IO + ex.getMessage());
    } catch (JRException ex) {
      throw new JasperReportViewException(ex, ERROR_GENERATE_REPORT_FAILED);
    }
  }

  private JasperDesign createCustomizedRequisitionLineSubreport(RequisitionTemplateDto template)
          throws JasperReportViewException {
    try (InputStream inputStream = getClass().getResourceAsStream(REQUISITION_LINE_REPORT_DIR)) {
      JasperDesign design = JRXmlLoader.load(inputStream);
      JRBand detail = design.getDetailSection().getBands()[0];
      JRBand header = design.getColumnHeader();

      Map<String, RequisitionTemplateColumnDto> columns =
              ReportUtils.getSortedTemplateColumnsForPrint(template.getColumnsMap());

      ReportUtils.customizeBandWithTemplateFields(detail, columns, design.getPageWidth(), 9);
      ReportUtils.customizeBandWithTemplateFields(header, columns, design.getPageWidth(), 9);

      return design;
    } catch (IOException err) {
      throw new JasperReportViewException(err, ERROR_IO, err.getMessage());
    } catch (JRException err) {
      throw new JasperReportViewException(err, ERROR_JASPER_FILE_FORMAT, err.getMessage());
    }
  }

  private void setCustomizedJasperTemplateForRequisitionReport(
          JasperReportsMultiFormatView jasperView) throws JasperReportViewException {
    try (InputStream inputStream = getClass().getResourceAsStream(REQUISITION_REPORT_DIR)) {
      File reportTempFile = createTempFile("requisitionReport_temp", ".jasper");
      JasperReport report = JasperCompileManager.compileReport(inputStream);

      try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
           ObjectOutputStream out = new ObjectOutputStream(bos)) {

        out.writeObject(report);
        writeByteArrayToFile(reportTempFile, bos.toByteArray());

        jasperView.setUrl(reportTempFile.toURI().toURL().toString());
      }
    } catch (IOException err) {
      throw new JasperReportViewException(err, ERROR_IO, err.getMessage());
    } catch (JRException err) {
      throw new JasperReportViewException(err, ERROR_JASPER_FILE_FORMAT, err.getMessage());
    }
  }

  private <T> T getIfPresent(BaseReferenceDataService<T> service, UUID id) {
    return Optional.ofNullable(id).isPresent() ? service.findOne(id) : null;
  }

  private ProcessingPeriodDto findNextPeriod(ProcessingPeriodDto period,
                                             Collection<ProcessingPeriodDto> periods) {
    periods = (periods != null) ? periods : periodReferenceDataService.search(
        period.getProcessingSchedule().getId(), null);
    return periods.stream()
        .filter(p -> p.getStartDate().isAfter(period.getEndDate()))
        .min(Comparator.comparing(ProcessingPeriodDto::getStartDate)).orElse(null);
  }

  private ProcessingPeriodDto findNextPeriod(String periodName) {
    List<ProcessingPeriodDto> periods = periodReferenceDataService.findAll();
    ProcessingPeriodDto period = periods.stream()
        .filter(p -> p.getName().equals(periodName))
        .findFirst().orElse(null);
    if (period != null) {
      ProcessingPeriodDto nextPeriod = findNextPeriod(period, periods);
      return nextPeriod;
    }
    return null;
  }

  byte[] fillAndExportReport(JasperReport compiledReport, Map<String, Object> params)
      throws JasperReportViewException {

    byte[] bytes;

    try {
      JasperPrint jasperPrint;

      try (Connection connection = replicationDataSource.getConnection()) {
        jasperPrint = JasperFillManager.fillReport(compiledReport, params,
            connection);
      }

      bytes = JasperExportManager.exportReportToPdf(jasperPrint);
    } catch (Exception ex) {
      throw new JasperReportViewException(ex, ERROR_GENERATE_REPORT_FAILED);
    }

    return bytes;
  }

  /**
   * Generate a report based on the Jasper template.
   * Create compiled report (".jasper" file) from bytes from Template entity, and get URL.
   * Using compiled report URL to fill in data and export to desired format.
   *
   * @param jasperTemplate template that will be used to generate a report
   * @param params  map of parameters
   * @return data of generated report
   */
  public byte[] generateReport(JasperTemplate jasperTemplate, Map<String, Object> params)
      throws JasperReportViewException {
    return fillAndExportReport(getReportFromTemplateData(jasperTemplate), params);
  }

  /**
   * Generate a report.
   * Using report URL to fill in data and export to desired format.
   *
   * @param templateUrl template url that will be used to generate a report
   * @param params  map of parameters
   * @return data of generated report
   */
  public ModelAndView generateReport(String templateUrl, Map<String, Object> params)
      throws JasperReportViewException {
    JasperReportsPdfView view = createJasperReportsPdfView();
    view.setUrl(compileReportAndGetUrl(templateUrl));
    view.setApplicationContext(appContext);
    return new ModelAndView(view, params);
  }

  private long getCount(List<StockCardDto> stockCards, Function<StockCardDto, String> mapper) {
    return stockCards.stream().map(mapper).distinct().count();
  }

  private DecimalFormat createDecimalFormat() {
    DecimalFormatSymbols decimalFormatSymbols = new DecimalFormatSymbols();
    decimalFormatSymbols.setGroupingSeparator(groupingSeparator.charAt(0));
    DecimalFormat decimalFormat = new DecimalFormat("", decimalFormatSymbols);
    decimalFormat.setGroupingSize(Integer.parseInt(groupingSize));
    return decimalFormat;
  }

  /**
   * Generate stock card summary report in PDF format.
   *
   * @param program  program id
   * @param facility facility id
   * @return generated stock card summary report.
   */
  public ModelAndView getStockCardSummariesReportView(UUID program, UUID facility)
      throws JasperReportViewException {
    List<StockCardSummaryDto> cardSummaries = stockCardSummariesDataService
        .findStockCardsSummaries(program, facility);
    Set cardIds = new HashSet();
    for (StockCardSummaryDto stockCardSummary : cardSummaries) {
      cardIds.addAll(stockCardSummary.getCanFulfillForMe()
          .stream()
          .map(c -> c.getStockCard().getId())
          .collect(Collectors.toList()));
    }
    List<StockCardDto> cards = stockCardReferenceDataService.findByIds(cardIds);
    cards.stream()
        .forEach(c -> {
          c.setOrderable(orderableReferenceDataService.findById(c.getOrderableId()));
          if (c.getLotId() != null) {
            c.setLot(lotReferenceDataService.findById(c.getLotId()));
          }
        });
    StockCardDto firstCard = cards.get(0);
    Map<String, Object> params = new HashMap<>();
    params.put("stockCardSummaries", cards);

    params.put("program", firstCard.getProgram());
    params.put("facility", firstCard.getFacility());
    //right now, each report can only be about one program, one facility
    //in the future we may want to support one reprot for multiple programs
    params.put("showProgram", getCount(cards, card -> card.getProgram().getId().toString()) > 1);
    params.put("showFacility", getCount(cards, card -> card.getFacility().getId().toString()) > 1);
    params.put("showLot", cards.stream().anyMatch(card -> card.getLotId() != null));
    params.put("dateFormat", dateFormat);
    params.put("dateTimeFormat", dateTimeFormat);
    params.put("decimalFormat", createDecimalFormat());

    return generateReport(CARD_SUMMARY_REPORT_URL, params);
  }

  private String compileReportAndGetUrl(String templateUrl) throws JasperReportViewException {
    try (InputStream inputStream = getClass().getResourceAsStream(templateUrl)) {
      JasperReport report = JasperCompileManager.compileReport(inputStream);

      return saveAndGetUrl(report, "report_temp");
    } catch (IOException ex) {
      throw new JasperReportViewException(ex, ERROR_IO + ex.getMessage());
    } catch (JRException ex) {
      throw new JasperReportViewException(ex, ERROR_GENERATE_REPORT_FAILED);
    }
  }

  private String saveAndGetUrl(JasperReport report, String templateName)
      throws IOException, JasperReportViewException {
    File reportTempFile;
    try {
      reportTempFile = createTempFile(templateName, ".jasper");
    } catch (IOException ex) {
      throw new JasperReportViewException(ex, ERROR_JASPER_FILE_CREATION);
    }

    try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
         ObjectOutputStream out = new ObjectOutputStream(bos)) {

      out.writeObject(report);
      writeByteArrayToFile(reportTempFile, bos.toByteArray());

      return reportTempFile.toURI().toURL().toString();
    }
  }

  protected JasperReportsPdfView createJasperReportsPdfView() {
    return new JasperReportsPdfView();
  }

  /**
   * Create ".jasper" file with byte array from Template.
   *
   * @return Url to ".jasper" file.
   */
  JasperReport getReportFromTemplateData(JasperTemplate jasperTemplate)
      throws JasperReportViewException {

    try (ObjectInputStream inputStream =
             new ObjectInputStream(new ByteArrayInputStream(jasperTemplate.getData()))) {

      return (JasperReport) inputStream.readObject();
    } catch (IOException ex) {
      throw new JasperReportViewException(ex, ERROR_IO + ex.getMessage());
    } catch (ClassNotFoundException ex) {
      throw new JasperReportViewException(
          ex, ERROR_REPORTING_CLASS_NOT_FOUND + JasperReport.class.getName());
    }
  }

  private void setFormatMappings(JasperReportsMultiFormatView jasperView) {
    Map<String, Class<? extends AbstractJasperReportsView>> formatMappings = new HashMap<>();
    formatMappings.put("csv", JasperReportsCsvView.class);
    formatMappings.put("html", JasperReportsHtmlView.class);
    formatMappings.put("pdf", JasperReportsPdfView.class);
    formatMappings.put("xls", JasperReportsXlsView.class);
    formatMappings.put("xlsx", JasperReportsXlsxView.class);
    jasperView.setFormatMappings(formatMappings);
  }
}
