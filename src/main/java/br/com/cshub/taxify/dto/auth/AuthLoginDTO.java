package br.com.cshub.taxify.dto.auth;

import jakarta.validation.constraints.NotEmpty;

import java.io.Serializable;


public class AuthLoginDTO implements Serializable {
    @NotEmpty
    private String auth;
    private String pinCode;

    public String getAuth() {
        return auth;
    }

    public void setAuth(String auth) {
        this.auth = auth;
    }

}
