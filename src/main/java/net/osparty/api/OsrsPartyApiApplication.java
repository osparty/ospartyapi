package net.osparty.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the OSParty advertising API — a small "bulletin board" the
 * RuneLite plugin posts open-party ads to and browses. It deliberately tracks no
 * party membership: the live roster runs peer-to-peer in the plugin, keyed by
 * the passphrase carried on each ad.
 */
@EnableScheduling
@SpringBootApplication
public class OsrsPartyApiApplication
{
	public static void main(String[] args)
	{
		SpringApplication.run(OsrsPartyApiApplication.class, args);
	}
}
