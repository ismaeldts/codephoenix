package com.challenge.codephoenix.application;

/** Solicitud del usuario que no se puede procesar; el mensaje indica cómo corregirla. */
public class InvalidRequestException extends RuntimeException {

	public InvalidRequestException(String message) {
		super(message);
	}

}
