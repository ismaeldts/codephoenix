package com.challenge.codephoenix.application;

public class JobNotFoundException extends RuntimeException {

	public JobNotFoundException(String jobId) {
		super("No existe la migración " + jobId);
	}

}
