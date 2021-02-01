package com.mycorp;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mycorp.support.CorreoElectronico;
import com.mycorp.support.DatosCliente;
import com.mycorp.support.MensajeriaService;
import com.mycorp.support.Poliza;
import com.mycorp.support.PolizaBasicoFromPolizaBuilder;
import com.mycorp.support.Ticket;
import com.mycorp.support.ValueCode;

import portalclientesweb.ejb.interfaces.PortalClientesWebEJBRemote;
import util.datos.PolizaBasico;
import util.datos.UsuarioAlta;

@Service
public class ZendeskService {

    /** The Constant LOG. */
    private static final Logger LOG = LoggerFactory.getLogger( ZendeskService.class );

    private static final String ESCAPED_LINE_SEPARATOR = "\\n";
    private static final String ESCAPE_ER = "\\";
    private static final String HTML_BR = "<br/>";
    @Value("#{envPC['zendesk.ticket']}")
    public String PETICION_ZENDESK= "";

    @Value("#{envPC['zendesk.token']}")
    public String TOKEN_ZENDESK= "";

    @Value("#{envPC['zendesk.url']}")
    public String URL_ZENDESK= "";

    @Value("#{envPC['zendesk.user']}")
    public String ZENDESK_USER= "";

    @Value("#{envPC['tarjetas.getDatos']}")
    public String TARJETAS_GETDATOS = "";

    @Value("#{envPC['cliente.getDatos']}")
    public String CLIENTE_GETDATOS = "";

    @Value("#{envPC['zendesk.error.mail.funcionalidad']}")
    public String ZENDESK_ERROR_MAIL_FUNCIONALIDAD = "";

    @Value("#{envPC['zendesk.error.destinatario']}")
    public String ZENDESK_ERROR_DESTINATARIO = "";

    private SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy");


    /** The portalclientes web ejb remote. */
    @Autowired
    // @Qualifier("portalclientesWebEJB")
    private PortalClientesWebEJBRemote portalclientesWebEJBRemote;

    /** The rest template. */
    @Autowired
    @Qualifier("restTemplateUTF8")
    private RestTemplate restTemplate;

    @Autowired
    @Qualifier( "emailService" )
    MensajeriaService emailService;

    /**
     * Crea un ticket en Zendesk. Si se ha informado el nÂº de tarjeta, obtiene los datos asociados a dicha tarjeta de un servicio externo.
     * @param usuarioAlta
     * @param userAgent
     */
    public String altaTicketZendesk(UsuarioAlta usuarioAlta, String userAgent){

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        StringBuilder datosBravo = new StringBuilder();
        String idCliente = null;
        StringBuilder clientName = new StringBuilder();
        StringBuilder datosUsuario = addDataUser(usuarioAlta, new StringBuilder(), userAgent);
       
        StringBuilder datosServicio = new StringBuilder();
        // Obtiene el idCliente de la tarjeta
        if(StringUtils.isNotBlank(usuarioAlta.getNumTarjeta())){
            try{
                String urlToRead = TARJETAS_GETDATOS + usuarioAlta.getNumTarjeta();
                ResponseEntity<String> res = restTemplate.getForEntity( urlToRead, String.class);
                if(res.getStatusCode() == HttpStatus.OK){
                    String dusuario = res.getBody();
                    clientName.append(dusuario);
                    idCliente = dusuario;
                    datosServicio.append("Datos recuperados del servicio de tarjeta:").append(ESCAPED_LINE_SEPARATOR).append(mapper.writeValueAsString(dusuario));
                }
            }catch(Exception e)
            {
                LOG.error("Error al obtener los datos de la tarjeta", e);
            }
        }
        else if(StringUtils.isNotBlank(usuarioAlta.getNumPoliza())){
            try
            {
                PolizaBasico polizaBasicoConsulta = new PolizaBasicoFromPolizaBuilder().withPoliza( fillPoliza(usuarioAlta) ).build();
                final util.datos.DetallePoliza detallePolizaResponse = portalclientesWebEJBRemote.recuperarDatosPoliza(polizaBasicoConsulta);

                clientName.append(detallePolizaResponse.getTomador().getNombre()).
                            append(" ").
                            append(detallePolizaResponse.getTomador().getApellido1()).
                            append(" ").
                            append(detallePolizaResponse.getTomador().getApellido2());

                idCliente = detallePolizaResponse.getTomador().getIdentificador();
                datosServicio.append("Datos recuperados del servicio de tarjeta:").append(ESCAPED_LINE_SEPARATOR).append(mapper.writeValueAsString(detallePolizaResponse));
            }catch(Exception e)
            {
                LOG.error("Error al obtener los datos de la poliza", e);
            }
        }

        try
        {
            // Obtenemos los datos del cliente
            DatosCliente cliente = restTemplate.getForObject("http://localhost:8080/test-endpoint", DatosCliente.class, idCliente);
            datosBravo = addDataBravo(new StringBuilder(),cliente);

        }catch(Exception e)
        {
            LOG.error("Error al obtener los datos en BRAVO del cliente", e);
        }

        String ticket = String.format(PETICION_ZENDESK, clientName.toString(), usuarioAlta.getEmail(), datosUsuario.toString()+datosBravo.toString()+
                parseJsonBravo(datosServicio));
        ticket = ticket.replaceAll("["+ESCAPED_LINE_SEPARATOR+"]", " ");

        try(
        		Zendesk zendesk = new Zendesk.Builder(URL_ZENDESK).setUsername(ZENDESK_USER).setToken(TOKEN_ZENDESK).build()){
            //Ticket
            Ticket petiZendesk = mapper.readValue(ticket, Ticket.class);
            zendesk.createTicket(petiZendesk);

        }catch(Exception e){
            LOG.error("Error al crear ticket ZENDESK", e);
            // Send email
            try
            {
                emailService.enviar(sendEmail(datosUsuario, datosBravo) );
            }catch(Exception ex){
                LOG.error("Error al enviar mail", ex);
            }
        }
        datosUsuario.append(datosBravo);

        return datosUsuario.toString();
    }

    private Poliza fillPoliza(UsuarioAlta usuarioAlta) {
        Poliza poliza = new Poliza();
        poliza.setNumPoliza(Integer.valueOf(usuarioAlta.getNumPoliza()));
        poliza.setNumColectivo(Integer.valueOf(usuarioAlta.getNumDocAcreditativo()));
        poliza.setCompania(1);

		return poliza;
	}

	private CorreoElectronico sendEmail(Object datosUsuario, Object datosBravo) {
    	CorreoElectronico correo = new CorreoElectronico( Long.parseLong(ZENDESK_ERROR_MAIL_FUNCIONALIDAD), "es" )
                .addParam(datosUsuario.toString().replaceAll(ESCAPE_ER+ESCAPED_LINE_SEPARATOR, HTML_BR))
                .addParam(datosBravo.toString().replaceAll(ESCAPE_ER+ESCAPED_LINE_SEPARATOR, HTML_BR));
    	 correo.setEmailA(ZENDESK_ERROR_DESTINATARIO);
    	 return correo;
	}

	private StringBuilder addDataBravo(StringBuilder datosBravo, DatosCliente cliente) throws ParseException {

    	datosBravo.append(ESCAPED_LINE_SEPARATOR + "Datos recuperados de BRAVO:" + ESCAPED_LINE_SEPARATOR + ESCAPED_LINE_SEPARATOR);
        datosBravo.append("TelÃ©fono: ").append(cliente.getGenTGrupoTmk()).append(ESCAPED_LINE_SEPARATOR);
        datosBravo.append("Feha de nacimiento: ").append(formatter.format(formatter.parse(cliente.getFechaNacimiento()))).append(ESCAPED_LINE_SEPARATOR);

        getTiposDocumentosRegistro().stream()
        .filter(td->td.getCode().equals(cliente.getGenCTipoDocumento().toString()))
        .forEach(td->{ datosBravo.append("Tipo de documento: ").append(td.getValue()).append(ESCAPED_LINE_SEPARATOR);});
        
        datosBravo.append("NÃºmero documento: ").append(cliente.getNumeroDocAcred()).append(ESCAPED_LINE_SEPARATOR);
        datosBravo.append("Tipo cliente: ");
        
        getCustomerType(cliente.getGenTTipoCliente(), datosBravo);
 
        datosBravo.append("ID estado del cliente: ").append(cliente.getGenTStatus()).append(ESCAPED_LINE_SEPARATOR);
        datosBravo.append("ID motivo de alta cliente: ").append(cliente.getIdMotivoAlta()).append(ESCAPED_LINE_SEPARATOR);
        datosBravo.append("Registrado: ").append((cliente.getfInactivoWeb() == null ? "SÃ­" : "No")).append(ESCAPED_LINE_SEPARATOR + ESCAPED_LINE_SEPARATOR);
		
        return datosBravo;
	}

	private StringBuilder getCustomerType(Integer genTTipoCliente, StringBuilder datosBravo) {
	       switch (genTTipoCliente) {
	        case 1:
	            datosBravo.append("POTENCIAL").append(ESCAPED_LINE_SEPARATOR);
	            break;
	        case 2:
	            datosBravo.append("REAL").append(ESCAPED_LINE_SEPARATOR);
	            break;
	        case 3:
	            datosBravo.append("PROSPECTO").append(ESCAPED_LINE_SEPARATOR);
	            break;
	        }
		return datosBravo;
	}

	private StringBuilder addDataUser(UsuarioAlta usuarioAlta, StringBuilder datosUsuario, String userAgent) {
        // AÃ±ade los datos del formulario
        if(StringUtils.isNotBlank(usuarioAlta.getNumPoliza())){
            datosUsuario.append("NÂº de poliza/colectivo: ").append(usuarioAlta.getNumPoliza()).append("/").append(usuarioAlta.getNumDocAcreditativo()).append(ESCAPED_LINE_SEPARATOR);
        }else{
            datosUsuario.append("NÂº tarjeta Sanitas o Identificador: ").append(usuarioAlta.getNumTarjeta()).append(ESCAPED_LINE_SEPARATOR);
        }
        datosUsuario.append("Tipo documento: ").append(usuarioAlta.getTipoDocAcreditativo()).append(ESCAPED_LINE_SEPARATOR);
        datosUsuario.append("NÂº documento: ").append(usuarioAlta.getNumDocAcreditativo()).append(ESCAPED_LINE_SEPARATOR);
        datosUsuario.append("Email personal: ").append(usuarioAlta.getEmail()).append(ESCAPED_LINE_SEPARATOR);
        datosUsuario.append("NÂº mÃ³vil: ").append(usuarioAlta.getNumeroTelefono()).append(ESCAPED_LINE_SEPARATOR);
        datosUsuario.append("User Agent: ").append(userAgent).append(ESCAPED_LINE_SEPARATOR);
		return datosUsuario;
	}

	public List< ValueCode > getTiposDocumentosRegistro() {
        return Arrays.asList( new ValueCode(), new ValueCode() ); // simulacion servicio externo
    }

    /**
     * MÃ©todo para parsear el JSON de respuesta de los servicios de tarjeta/pÃ³liza
     *
     * @param resBravo
     * @return
     */
    private String parseJsonBravo(StringBuilder resBravo)
    {
        return resBravo.toString().replaceAll("[\\[\\]\\{\\}\\\"\\r]", "").replaceAll(ESCAPED_LINE_SEPARATOR, ESCAPE_ER + ESCAPED_LINE_SEPARATOR);
    }
}