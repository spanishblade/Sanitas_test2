package com.mycorp.support;

import org.apache.commons.lang3.StringUtils;
import org.springframework.util.ObjectUtils;

import util.datos.PolizaBasico;

public class PolizaBasicoFromPolizaBuilder {
    /** The poliza. */
    private Poliza poliza;

    /**
     * With poliza.
     *
     * @param poliza
     *            the poliza
     * @return the poliza basico from poliza builder
     */
    public PolizaBasicoFromPolizaBuilder withPoliza( final Poliza poliza ) {
        this.poliza = poliza;
        return this;
    }

    /**
     * Builds the.
     *
     * @return the poliza basico
     */
    public PolizaBasico build() {

        PolizaBasico polizaBasico = null;

        if( !ObjectUtils.isEmpty(poliza) ) {
            polizaBasico = new PolizaBasico();
            polizaBasico.setCompania( poliza.getCompania() );
            polizaBasico.setNumPoliza( poliza.getNumPoliza() );
            polizaBasico.setNumColectivo( poliza.getNumColectivo() );
            if (!ObjectUtils.isEmpty(poliza.getPlanInfo()) && StringUtils.isNotBlank(poliza.getPlanInfo().getCode())) {
                polizaBasico.setPlan(Integer.valueOf(poliza.getPlanInfo().getCode()));
            }
        }

        return polizaBasico;
    }
}
