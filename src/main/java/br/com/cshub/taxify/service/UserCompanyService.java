package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.AbstractCrudService;
import br.com.cshub.taxify.common.MessageService;

import br.com.cshub.taxify.domain.user_company.UserCompany;
import br.com.cshub.taxify.domain.user_company.UserCompanyId;
import br.com.cshub.taxify.dto.user_company.UserCompanyDTO;
import br.com.cshub.taxify.dto.user_company.UserCompanyFilterDTO;
import br.com.cshub.taxify.mapper.UserCompanyMapper;
import br.com.cshub.taxify.repository.UserCompanyRepository;
import br.com.cshub.taxify.search.UserCompanySpecification;
import br.com.cshub.taxify.validation.UserCompanyValidation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class UserCompanyService extends AbstractCrudService<UserCompany, UserCompanyId, UserCompanyDTO, UserCompanyFilterDTO> {


    @Autowired
    protected UserCompanyService(UserCompanyRepository repository, UserCompanyMapper mapper, UserCompanyValidation validation, MessageService messageService, UserCompanySpecification specification) {
        super(repository, mapper, validation, messageService, specification);
        setMessageNotFound("error.Company.not.found");
    }

}
