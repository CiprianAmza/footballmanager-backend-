package com.footballmanagergamesimulator.user;

import lombok.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Data
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class UserService {

    @Autowired
    public UserRepository userRepository;
    String totalChat = "", lastMessageFrom = "", text = "";

    public User getCurrentAuthenticatedUser() {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        Authentication authentication = securityContext.getAuthentication();
        User user = null;
        if (authentication != null) {

            try {
                UserDetails userDetails = (UserDetails) authentication.getPrincipal();
                user = userRepository.findByUsername(userDetails.getUsername())
                        .orElse(null);
            } catch(ClassCastException e) {
                return user;
            }


        }
        return user;
    }

    public void setCurrentAuthenticatedUser(){

    }

    public void saveUser(UserDto userDto) {
        Optional<User> existing = userRepository.findByUsername(userDto.getUsername());
        if (existing.isPresent()) {
            throw new IllegalArgumentException("Username already taken");
        }

        if (!userDto.getEmail().contains("@")) {
            throw new IllegalArgumentException("Invalid email");
        }

        if (userDto.getFirstName().length() < 4) {
            throw new IllegalArgumentException("First name should have at least 6 characters");
        }

        if (userDto.getLastName().length() < 3) {
            throw new IllegalArgumentException("Last name should have at least 3 characters");
        }

        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        String encodedPassword = passwordEncoder.encode(userDto.getPassword());

        User user = new User();
        user.setPassword(encodedPassword);
        user.setEmail(userDto.getEmail());
        user.setUsername(userDto.getUsername());
        user.setFirstName(userDto.getFirstName());
        user.setLastName(userDto.getLastName());

        userRepository.save(user);
    }


}
