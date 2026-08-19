package com.clipshare.user;

import com.clipshare.auth.AppUserPrincipal;
import com.clipshare.user.dto.UserProfileResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/me")
    public UserProfileResponse me(@AuthenticationPrincipal AppUserPrincipal principal) {
        return UserProfileResponse.from(principal.getUser());
    }
}
