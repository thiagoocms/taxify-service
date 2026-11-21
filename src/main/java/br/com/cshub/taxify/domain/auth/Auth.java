package br.com.cshub.taxify.domain.auth;

import br.com.cshub.taxify.domain.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
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
        this.setAccessProfiles(user.getAccessProfiles());
        this.setDeleted(user.isDeleted());
        this.setAccountingFirm(user.getAccountingFirm());
        this.setUserCompanies(user.getUserCompanies());
    }

    @Override
    /** The user type (ADMIN/EMPLOYEE/USER) plus every permission - what SecurityConfig checks. */
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(this.getProfile().name()));
        this.getPermissions().forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission.name())));
        return authorities;
    }

    @Override
    public String getUsername() {
        return getLogin();
    }

}
