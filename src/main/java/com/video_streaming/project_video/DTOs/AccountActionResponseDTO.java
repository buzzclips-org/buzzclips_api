package com.video_streaming.project_video.DTOs;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.io.Serializable;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccountActionResponseDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private boolean success;
    private String message;
    private String actionLink;
    private AccountSecurityDTO account;

    public static AccountActionResponseDTO success(String message, AccountSecurityDTO account) {
        return AccountActionResponseDTO.builder()
                .success(true)
                .message(message)
                .account(account)
                .build();
    }

    public static AccountActionResponseDTO success(String message, String actionLink, AccountSecurityDTO account) {
        return AccountActionResponseDTO.builder()
                .success(true)
                .message(message)
                .actionLink(actionLink)
                .account(account)
                .build();
    }

    public static AccountActionResponseDTO failure(String message) {
        return AccountActionResponseDTO.builder()
                .success(false)
                .message(message)
                .build();
    }
}
