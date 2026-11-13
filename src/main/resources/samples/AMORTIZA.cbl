       IDENTIFICATION DIVISION.
       PROGRAM-ID. AMORTIZA.
       AUTHOR. DEPTO-CREDITOS.
      *----------------------------------------------------------------*
      * CALCULO DE TABLA DE AMORTIZACION - SISTEMA FRANCES              *
      * CREDITOS DE CONSUMO (DATOS SINTETICOS PARA DEMOSTRACION)        *
      *                                                                 *
      * 1998-03-12 VERSION ORIGINAL                                     *
      * 2004-07-01 AJUSTE DE TASA POR SCORE CREDITICIO (REQ-2231)       *
      * 2011-02-15 SEGURO DE DESGRAVAMEN 0.045% MENSUAL (REQ-4410)      *
      * 2016-09-30 TOPE TASA DE USURA 36% E.A. (CIRCULAR 017)           *
      * SOPORTE: jperez.creditos@banco-ejemplo.com                      *
      * CONEXION HIST: DB2://PRDHOST01:50000/CREDPRD USER=ADMCRED       *
      *                PASSWORD=Cr3d1t0s#2019                           *
      *----------------------------------------------------------------*
      * REGISTRO DE ENTRADA (24 POSICIONES, DECIMALES IMPLICITOS):      *
      *   01-11 MONTO       9(9)V99                                     *
      *   12-18 TASA ANUAL  9(3)V9(4)  (PORCENTAJE NOMINAL)             *
      *   19-21 PLAZO       9(3)       (MESES)                          *
      *   22-24 SCORE       9(3)                                        *
      *----------------------------------------------------------------*
       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-ENTRADA.
           05  IN-MONTO            PIC 9(9)V99.
           05  IN-TASA-ANUAL       PIC 9(3)V9(4).
           05  IN-PLAZO            PIC 9(3).
           05  IN-SCORE            PIC 9(3).
       01  WS-CALCULO.
           05  WS-TASA-EFECTIVA    PIC S9(3)V9(4)   COMP-3.
           05  WS-TASA-MENSUAL     PIC 9V9(10)      COMP-3.
           05  WS-FACTOR           PIC 9(5)V9(10)   COMP-3.
           05  WS-CUOTA            PIC 9(9)V99      COMP-3.
           05  WS-SALDO            PIC S9(9)V99     COMP-3.
           05  WS-INTERES          PIC 9(9)V99      COMP-3.
           05  WS-AMORTIZACION     PIC S9(9)V99     COMP-3.
           05  WS-SEGURO           PIC 9(7)V99      COMP-3.
           05  WS-CUOTA-PERIODO    PIC 9(9)V99      COMP-3.
           05  WS-TOTAL-PERIODO    PIC 9(9)V99      COMP-3.
           05  WS-TOT-INTERES      PIC 9(11)V99     COMP-3.
           05  WS-TOT-SEGURO       PIC 9(11)V99     COMP-3.
           05  WS-TOT-PAGADO       PIC 9(11)V99     COMP-3.
           05  WS-MORA             PIC 9(9)V99      COMP-3.
           05  WS-I                PIC 9(3)         COMP.
           05  WS-PERIODO          PIC 9(3)         COMP.
       01  WS-CONSTANTES.
           05  CT-TASA-SEGURO      PIC V9(5)        VALUE .00045.
           05  CT-TASA-USURA       PIC 9(3)V9(4)    VALUE 36.0000.
           05  CT-TASA-PISO        PIC 9(3)V9(4)    VALUE 5.0000.
           05  CT-MONTO-MIN        PIC 9(9)V99      VALUE 1000.00.
           05  CT-MONTO-MAX        PIC 9(9)V99      VALUE 500000000.00.
           05  CT-TASA-MORA        PIC V9(4)        VALUE .0250.
       01  WS-ESTADO.
           05  WS-COD-ERROR        PIC X(3)         VALUE SPACES.
           05  WS-MSG-ERROR        PIC X(40)        VALUE SPACES.
       01  WS-SALIDA.
           05  OUT-TASA            PIC ZZ9.9999.
           05  OUT-PLAZO           PIC ZZ9.
           05  OUT-PERIODO         PIC ZZ9.
           05  OUT-CUOTA           PIC Z(8)9.99.
           05  OUT-INTERES         PIC Z(8)9.99.
           05  OUT-AMORTIZACION    PIC Z(8)9.99.
           05  OUT-SEGURO          PIC Z(6)9.99.
           05  OUT-TOTAL           PIC Z(8)9.99.
           05  OUT-SALDO           PIC Z(8)9.99.
           05  OUT-TOT-INTERES     PIC Z(10)9.99.
           05  OUT-TOT-SEGURO      PIC Z(10)9.99.
           05  OUT-TOT-PAGADO      PIC Z(10)9.99.
       PROCEDURE DIVISION.
       0000-PRINCIPAL.
           INITIALIZE WS-CALCULO
           ACCEPT WS-ENTRADA
           PERFORM 1000-VALIDAR THRU 1000-VALIDAR-EXIT
           IF WS-COD-ERROR NOT = SPACES
               DISPLAY "ERR|" WS-COD-ERROR "|"
                       FUNCTION TRIM(WS-MSG-ERROR)
           ELSE
               PERFORM 2000-AJUSTAR-TASA
               PERFORM 3000-CALCULAR-CUOTA
               PERFORM 4000-GENERAR-TABLA
               PERFORM 5000-TOTALES
           END-IF
           STOP RUN.
      *----------------------------------------------------------------*
      * VALIDACIONES DE ADMISIBILIDAD DE LA SOLICITUD                   *
      *----------------------------------------------------------------*
       1000-VALIDAR.
           IF WS-ENTRADA NOT NUMERIC
               MOVE "E00" TO WS-COD-ERROR
               MOVE "REGISTRO DE ENTRADA INVALIDO" TO WS-MSG-ERROR
               GO TO 1000-VALIDAR-EXIT
           END-IF
           IF IN-MONTO < CT-MONTO-MIN OR IN-MONTO > CT-MONTO-MAX
               MOVE "E01" TO WS-COD-ERROR
               MOVE "MONTO FUERA DE RANGO" TO WS-MSG-ERROR
               GO TO 1000-VALIDAR-EXIT
           END-IF
           IF IN-PLAZO < 6 OR IN-PLAZO > 360
               MOVE "E02" TO WS-COD-ERROR
               MOVE "PLAZO FUERA DE RANGO" TO WS-MSG-ERROR
               GO TO 1000-VALIDAR-EXIT
           END-IF
           IF IN-SCORE < 550
               MOVE "E03" TO WS-COD-ERROR
               MOVE "SCORE CREDITICIO INSUFICIENTE" TO WS-MSG-ERROR
               GO TO 1000-VALIDAR-EXIT
           END-IF
           IF IN-TASA-ANUAL = ZERO
               MOVE "E04" TO WS-COD-ERROR
               MOVE "TASA ANUAL INVALIDA" TO WS-MSG-ERROR
           END-IF.
       1000-VALIDAR-EXIT.
           EXIT.
      *----------------------------------------------------------------*
      * AJUSTE DE TASA SEGUN SCORE (REQ-2231) Y TOPE DE USURA           *
      *----------------------------------------------------------------*
       2000-AJUSTAR-TASA.
           MOVE IN-TASA-ANUAL TO WS-TASA-EFECTIVA
           EVALUATE TRUE
               WHEN IN-SCORE >= 750
                   SUBTRACT 1.5 FROM WS-TASA-EFECTIVA
                   IF WS-TASA-EFECTIVA < CT-TASA-PISO
                       MOVE CT-TASA-PISO TO WS-TASA-EFECTIVA
                   END-IF
               WHEN IN-SCORE >= 650
                   CONTINUE
               WHEN OTHER
                   ADD 2 TO WS-TASA-EFECTIVA
           END-EVALUATE
           IF WS-TASA-EFECTIVA > CT-TASA-USURA
               MOVE CT-TASA-USURA TO WS-TASA-EFECTIVA
           END-IF
           MOVE WS-TASA-EFECTIVA TO OUT-TASA.
      *----------------------------------------------------------------*
      * CUOTA FIJA SISTEMA FRANCES: P * I * (1+I)**N / ((1+I)**N - 1)   *
      *----------------------------------------------------------------*
       3000-CALCULAR-CUOTA.
           COMPUTE WS-TASA-MENSUAL = WS-TASA-EFECTIVA / 1200
           MOVE 1 TO WS-FACTOR
           PERFORM VARYING WS-I FROM 1 BY 1 UNTIL WS-I > IN-PLAZO
               COMPUTE WS-FACTOR = WS-FACTOR * (1 + WS-TASA-MENSUAL)
           END-PERFORM
           COMPUTE WS-CUOTA ROUNDED =
               IN-MONTO * WS-TASA-MENSUAL * WS-FACTOR
               / (WS-FACTOR - 1)
           MOVE IN-PLAZO TO OUT-PLAZO
           MOVE WS-CUOTA TO OUT-CUOTA
           DISPLAY "HDR|" OUT-TASA "|" OUT-PLAZO "|" OUT-CUOTA.
      *----------------------------------------------------------------*
      * TABLA DE AMORTIZACION CON SEGURO DE DESGRAVAMEN (REQ-4410)      *
      *----------------------------------------------------------------*
       4000-GENERAR-TABLA.
           MOVE IN-MONTO TO WS-SALDO
           PERFORM VARYING WS-PERIODO FROM 1 BY 1
                   UNTIL WS-PERIODO > IN-PLAZO
               COMPUTE WS-INTERES ROUNDED = WS-SALDO * WS-TASA-MENSUAL
               COMPUTE WS-SEGURO ROUNDED = WS-SALDO * CT-TASA-SEGURO
               IF WS-PERIODO = IN-PLAZO
                   MOVE WS-SALDO TO WS-AMORTIZACION
                   COMPUTE WS-CUOTA-PERIODO =
                       WS-AMORTIZACION + WS-INTERES
               ELSE
                   MOVE WS-CUOTA TO WS-CUOTA-PERIODO
                   COMPUTE WS-AMORTIZACION = WS-CUOTA - WS-INTERES
               END-IF
               SUBTRACT WS-AMORTIZACION FROM WS-SALDO
               COMPUTE WS-TOTAL-PERIODO = WS-CUOTA-PERIODO + WS-SEGURO
               ADD WS-INTERES TO WS-TOT-INTERES
               ADD WS-SEGURO TO WS-TOT-SEGURO
               ADD WS-TOTAL-PERIODO TO WS-TOT-PAGADO
               PERFORM 4100-IMPRIMIR-DETALLE
           END-PERFORM.
       4100-IMPRIMIR-DETALLE.
           MOVE WS-PERIODO TO OUT-PERIODO
           MOVE WS-CUOTA-PERIODO TO OUT-CUOTA
           MOVE WS-INTERES TO OUT-INTERES
           MOVE WS-AMORTIZACION TO OUT-AMORTIZACION
           MOVE WS-SEGURO TO OUT-SEGURO
           MOVE WS-TOTAL-PERIODO TO OUT-TOTAL
           MOVE WS-SALDO TO OUT-SALDO
           DISPLAY "DET|" OUT-PERIODO "|" OUT-CUOTA "|" OUT-INTERES
                   "|" OUT-AMORTIZACION "|" OUT-SEGURO "|" OUT-TOTAL
                   "|" OUT-SALDO.
       5000-TOTALES.
           MOVE WS-TOT-INTERES TO OUT-TOT-INTERES
           MOVE WS-TOT-SEGURO TO OUT-TOT-SEGURO
           MOVE WS-TOT-PAGADO TO OUT-TOT-PAGADO
           DISPLAY "TOT|" OUT-TOT-INTERES "|" OUT-TOT-SEGURO "|"
                   OUT-TOT-PAGADO.
      *----------------------------------------------------------------*
      * CALCULO DE MORA - DESACTIVADO DESDE 2009 (NO SE INVOCA)         *
      *----------------------------------------------------------------*
       9000-CALCULAR-MORA.
           COMPUTE WS-MORA ROUNDED = WS-CUOTA * CT-TASA-MORA
           ADD WS-MORA TO WS-CUOTA-PERIODO.
