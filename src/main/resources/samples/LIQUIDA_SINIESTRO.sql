--------------------------------------------------------------------------------
-- LIQUIDACION DE SINIESTROS - SEGUROS GENERALES (DATOS SINTETICOS PARA DEMO)
--
-- 2003-05-20 VERSION ORIGINAL
-- 2008-11-03 PERIODO DE CARENCIA POR PRODUCTO (REQ-1290)
-- 2012-06-14 SUBLIMITE 50% INUNDACION EN HOGAR (REQ-2207)
-- 2015-02-10 SE DESACTIVA IVA SOBRE HONORARIOS (REQ-0877)
-- 2019-09-01 RETENCION 2.5% PARA PAGOS MAYORES A 10.000 (RES. 044)
-- SOPORTE: mgomez.siniestros@aseguradora-ejemplo.com
-- CONEXION BATCH: user=LIQUIDADOR password=Sin1estr0s#2018 host 10.20.30.40
--------------------------------------------------------------------------------

CREATE TABLE POLIZAS (
    POLIZA_ID        NUMBER(10)   PRIMARY KEY,
    TIPO_PRODUCTO    VARCHAR2(10) NOT NULL,     -- AUTO, HOGAR, SALUD
    ESTADO           VARCHAR2(10) NOT NULL,     -- VIGENTE, SUSPENDIDA, CANCELADA
    FECHA_INICIO     DATE         NOT NULL,
    SUMA_ASEGURADA   NUMBER(12,2) NOT NULL,
    DEDUCIBLE        NUMBER(12,2) NOT NULL,
    PORC_COASEGURO   NUMBER(5,2)  DEFAULT 0 NOT NULL
);

CREATE TABLE SINIESTROS (
    SINIESTRO_ID     NUMBER(10)   PRIMARY KEY,
    POLIZA_ID        NUMBER(10)   NOT NULL,
    FECHA_OCURRENCIA DATE         NOT NULL,
    MONTO_RECLAMADO  NUMBER(12,2) NOT NULL,
    CAUSA            VARCHAR2(20) NOT NULL,
    ESTADO           VARCHAR2(12) NOT NULL,     -- ABIERTO, LIQUIDADO, SIN_PAGO, RECHAZADO
    MONTO_LIQUIDADO  NUMBER(12,2)
);

CREATE TABLE PAGOS_SINIESTRO (
    SINIESTRO_ID     NUMBER(10)   PRIMARY KEY,
    MONTO            NUMBER(12,2) NOT NULL,
    RETENCION        NUMBER(12,2) NOT NULL,
    NETO             NUMBER(12,2) NOT NULL
);

CREATE OR REPLACE PROCEDURE LIQUIDA_SINIESTRO (
    p_siniestro_id  IN  NUMBER,
    p_monto_pagar   OUT NUMBER,
    p_estado        OUT VARCHAR2
) AS
    v_sin            SINIESTROS%ROWTYPE;
    v_pol            POLIZAS%ROWTYPE;
    v_dias_carencia  PLS_INTEGER;
    v_base           NUMBER(12,2);
    v_factor         NUMBER(5,4);
    v_tope           NUMBER(12,2);
    v_retencion      NUMBER(12,2) := 0;

    -- IVA SOBRE HONORARIOS: DESACTIVADO DESDE 2015 (REQ-0877), YA NO SE INVOCA
    FUNCTION CALCULA_IVA (p_monto NUMBER) RETURN NUMBER IS
    BEGIN
        RETURN ROUND(p_monto * 0.19, 2);
    END CALCULA_IVA;
BEGIN
    BEGIN
        SELECT * INTO v_sin FROM SINIESTROS WHERE SINIESTRO_ID = p_siniestro_id;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE_APPLICATION_ERROR(-20010, 'SINIESTRO NO EXISTE');
    END;

    IF v_sin.ESTADO <> 'ABIERTO' THEN
        RAISE_APPLICATION_ERROR(-20011, 'SINIESTRO YA PROCESADO');
    END IF;

    SELECT * INTO v_pol FROM POLIZAS WHERE POLIZA_ID = v_sin.POLIZA_ID;

    p_estado := 'RECHAZADO';
    p_monto_pagar := 0;

    -- POLIZA DEBE ESTAR VIGENTE
    IF v_pol.ESTADO <> 'VIGENTE' THEN
        GOTO fin_proceso;
    END IF;

    -- PERIODO DE CARENCIA (REQ-1290)
    IF v_pol.TIPO_PRODUCTO = 'SALUD' THEN
        v_dias_carencia := 90;
    ELSE
        v_dias_carencia := 30;
    END IF;
    IF v_sin.FECHA_OCURRENCIA < v_pol.FECHA_INICIO + v_dias_carencia THEN
        GOTO fin_proceso;
    END IF;

    -- EXCLUSIONES GENERALES Y DE AUTOMOVILES
    IF v_sin.CAUSA IN ('GUERRA', 'DOLO')
       OR (v_pol.TIPO_PRODUCTO = 'AUTO' AND v_sin.CAUSA = 'COMPETENCIA') THEN
        GOTO fin_proceso;
    END IF;

    -- DEDUCIBLE Y COASEGURO
    v_base := GREATEST(v_sin.MONTO_RECLAMADO - v_pol.DEDUCIBLE, 0);
    v_factor := 1 - v_pol.PORC_COASEGURO / 100;
    v_base := v_base * v_factor;

    -- TOPE: SUMA ASEGURADA, CON SUBLIMITE DE INUNDACION EN HOGAR (REQ-2207)
    v_tope := v_pol.SUMA_ASEGURADA;
    IF v_pol.TIPO_PRODUCTO = 'HOGAR' AND v_sin.CAUSA = 'INUNDACION' THEN
        v_tope := v_pol.SUMA_ASEGURADA * 0.5;
    END IF;
    p_monto_pagar := LEAST(v_base, v_tope);

    IF p_monto_pagar = 0 THEN
        p_estado := 'SIN_PAGO';
    ELSE
        p_estado := 'LIQUIDADO';
        -- RETENCION (RES. 044)
        IF p_monto_pagar > 10000 THEN
            v_retencion := ROUND(p_monto_pagar * 0.025, 2);
        END IF;
        INSERT INTO PAGOS_SINIESTRO (SINIESTRO_ID, MONTO, RETENCION, NETO)
        VALUES (p_siniestro_id, p_monto_pagar, v_retencion, p_monto_pagar - v_retencion);
    END IF;

    <<fin_proceso>>
    UPDATE SINIESTROS
       SET ESTADO = p_estado,
           MONTO_LIQUIDADO = p_monto_pagar
     WHERE SINIESTRO_ID = p_siniestro_id;
END LIQUIDA_SINIESTRO;
/
