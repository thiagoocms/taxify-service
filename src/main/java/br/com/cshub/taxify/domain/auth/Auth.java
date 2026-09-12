package br.com.cshub.taxify.domain.auth;

import br.com.cshub.taxify.domain.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public class Auth extends User implements UserDetails {

    public Auth() {
    }

    public Auth(User user) {
        this.setId(user.getId());
        this.setName(user.getName());
        this.setDocument(user.getDocument());
        this.setLogin(user.getLogin());
        this.setPassword(user.getPassword());
        this.setEmail(user.getEmail());
        this.setProfile(user.getProfile());
        this.setDeleted(user.isDeleted());
        this.setAccountingFirm(user.getAccountingFirm());
        this.setUserCompanies(user.getUserCompanies());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(this.getProfile().toString()));
    }

    @Override
    public String getUsername() {
        return getLogin();
    }

}
